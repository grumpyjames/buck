/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.DexProducedFromJavaLibrary.BuildOutput;
import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.jvm.core.JvmLibrary;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.keys.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * {@link DexProducedFromJavaLibrary} is a {@link BuildRule} that serves a
 * very specific purpose: it takes a {@link JvmLibrary} and dexes the output of the
 * {@link JvmLibrary} if its list of classes is non-empty. Because it is expected to be used with
 * pre-dexing, we always pass the {@code --force-jumbo} flag to {@code dx} in this buildable.
 * <p>
 * Most {@link BuildRule}s can determine the (possibly null) path to their output file from their
 * definition. This is an anomaly because we do not know whether this will write a {@code .dex} file
 * until runtime. Unfortunately, because there is no such thing as an empty {@code .dex} file, we
 * cannot write a meaningful "dummy .dex" if there are no class files to pass to {@code dx}.
 */
public class DexProducedFromJavaLibrary extends AbstractBuildRule
    implements AbiRule, HasBuildTarget, InitializableFromDisk<BuildOutput> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Function<String, HashCode> TO_HASHCODE =
      new Function<String, HashCode>() {
        @Override
        public HashCode apply(String input) {
          return HashCode.fromString(input);
        }
      };

  @VisibleForTesting
  static final String LINEAR_ALLOC_KEY_ON_DISK_METADATA = "linearalloc";
  static final String CLASSNAMES_TO_HASHES = "classnames_to_hashes";

  private final JvmLibrary jvmLibrary;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  @VisibleForTesting
  DexProducedFromJavaLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      JvmLibrary jvmLibrary) {
    super(params, resolver);
    this.jvmLibrary = jvmLibrary;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new RmStep(getProjectFilesystem(), getPathToDex(), /* shouldForceDeletion */ true));

    // Make sure that the buck-out/gen/ directory exists for this.buildTarget.
    steps.add(new MkdirStep(getProjectFilesystem(), getPathToDex().getParent()));

    // If there are classes, run dx.
    final ImmutableSortedMap<String, HashCode> classNamesToHashes =
        jvmLibrary.getClassNamesToHashes();
    final boolean hasClassesToDx = !classNamesToHashes.isEmpty();
    final Supplier<Integer> linearAllocEstimate;
    if (hasClassesToDx) {
      Path pathToOutputFile = jvmLibrary.getPathToOutput();
      EstimateLinearAllocStep estimate = new EstimateLinearAllocStep(
          getProjectFilesystem(),
          pathToOutputFile);
      steps.add(estimate);
      linearAllocEstimate = estimate;

      // To be conservative, use --force-jumbo for these intermediate .dex files so that they can be
      // merged into a final classes.dex that uses jumbo instructions.
      DxStep dx = new DxStep(
          getProjectFilesystem(),
          getPathToDex(),
          Collections.singleton(pathToOutputFile),
          EnumSet.of(
              DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
              DxStep.Option.RUN_IN_PROCESS,
              DxStep.Option.NO_OPTIMIZE,
              DxStep.Option.FORCE_JUMBO));
      steps.add(dx);

      // The `DxStep` delegates to android tools to build a ZIP with timestamps in it, making
      // the output non-deterministic.  So use an additional scrubbing step to zero these out.
      steps.add(new ZipScrubberStep(getProjectFilesystem(), getPathToDex()));

    } else {
      linearAllocEstimate = Suppliers.ofInstance(0);
    }

    // Run a step to record artifacts and metadata. The values recorded depend upon whether dx was
    // run.
    String stepName = hasClassesToDx ? "record_dx_success" : "record_empty_dx";
    AbstractExecutionStep recordArtifactAndMetadataStep = new AbstractExecutionStep(stepName) {
      @Override
      public int execute(ExecutionContext context) throws IOException {
        if (hasClassesToDx) {
          buildableContext.recordArtifact(getPathToDex());
        }

        buildableContext.addMetadata(LINEAR_ALLOC_KEY_ON_DISK_METADATA,
            String.valueOf(linearAllocEstimate.get()));

        // Record the classnames to hashes map.
        buildableContext.addMetadata(
            CLASSNAMES_TO_HASHES,
            context.getObjectMapper().writeValueAsString(
                Maps.transformValues(classNamesToHashes, Functions.toStringFunction())));

        return 0;
      }
    };
    steps.add(recordArtifactAndMetadataStep);

    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    int linearAllocEstimate = Integer.parseInt(
        onDiskBuildInfo.getValue(LINEAR_ALLOC_KEY_ON_DISK_METADATA).get());
    Map<String, String> map =
        MAPPER.readValue(
            onDiskBuildInfo.getValue(CLASSNAMES_TO_HASHES).get(),
            new TypeReference<Map<String, String>>() {
            });
    Map<String, HashCode> classnamesToHashes = Maps.transformValues(map, TO_HASHCODE);
    return new BuildOutput(linearAllocEstimate, ImmutableSortedMap.copyOf(classnamesToHashes));
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  static class BuildOutput {
    private final int linearAllocEstimate;
    private final ImmutableSortedMap<String, HashCode> classnamesToHashes;
    BuildOutput(
        int linearAllocEstimate,
        ImmutableSortedMap<String, HashCode> classnamesToHashes) {
      this.linearAllocEstimate = linearAllocEstimate;
      this.classnamesToHashes = classnamesToHashes;
    }
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    // A .dex file is not guaranteed to be generated, so we return null to be conservative.
    return null;
  }

  public Path getPathToDex() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.dex.jar");
  }

  public boolean hasOutput() {
    return !getClassNames().isEmpty();
  }

  ImmutableSortedMap<String, HashCode> getClassNames() {
    // TODO(mbolin): Assert that this Buildable has been built. Currently, there is no way to do
    // that from a Buildable (but there is from an AbstractCachingBuildRule).
    return buildOutputInitializer.getBuildOutput().classnamesToHashes;
  }

  int getLinearAllocEstimate() {
    return buildOutputInitializer.getBuildOutput().linearAllocEstimate;
  }

  /**
   * The only dep for this rule should be {@link #jvmLibrary}. Therefore, the ABI key for the deps
   * of this buildable is the hash of the {@code .class} files for {@link #jvmLibrary}.
   */
  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return computeAbiKey(jvmLibrary.getClassNamesToHashes());
  }

  @VisibleForTesting
  static Sha1HashCode computeAbiKey(ImmutableSortedMap<String, HashCode> classNames) {
    Hasher hasher = Hashing.sha1().newHasher();
    for (Map.Entry<String, HashCode> entry : classNames.entrySet()) {
      hasher.putUnencodedChars(entry.getKey());
      hasher.putByte((byte) 0);
      hasher.putUnencodedChars(entry.getValue().toString());
      hasher.putByte((byte) 0);
    }
    return Sha1HashCode.of(hasher.hash().toString());
  }
}
