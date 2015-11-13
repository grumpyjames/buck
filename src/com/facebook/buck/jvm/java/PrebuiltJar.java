/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JvmLibrary;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;

@BuildsAnnotationProcessor
public class PrebuiltJar extends AbstractBuildRule
    implements AndroidPackageable, ExportDependencies, HasClasspathEntries,
    InitializableFromDisk<JvmLibrary.Data>, JvmLibrary {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final SourcePath binaryJar;
  private final SourcePath abiJar;
  private final Path copiedBinaryJar;
  @AddToRuleKey
  private final Optional<SourcePath> sourceJar;
  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final Optional<SourcePath> gwtJar;
  @AddToRuleKey
  private final Optional<String> javadocUrl;
  @AddToRuleKey
  private final Optional<String> mavenCoords;
  private final Path internalAbiJar;
  private final Supplier<ImmutableSetMultimap<JvmLibrary, Path>>
      transitiveClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<JvmLibrary>> transitiveClasspathDepsSupplier;

  private final Supplier<ImmutableSetMultimap<JvmLibrary, Path>>
      declaredClasspathEntriesSupplier;

  private final BuildOutputInitializer<Data> buildOutputInitializer;

  public PrebuiltJar(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath binaryJar,
      SourcePath abiJar,
      Optional<SourcePath> sourceJar,
      Optional<SourcePath> gwtJar,
      Optional<String> javadocUrl,
      Optional<String> mavenCoords) {
    super(params, resolver);
    this.binaryJar = binaryJar;
    this.abiJar = abiJar;
    this.sourceJar = sourceJar;
    this.gwtJar = gwtJar;
    this.javadocUrl = javadocUrl;
    this.mavenCoords = mavenCoords;

    this.internalAbiJar = BuildTargets.getGenPath(getBuildTarget(), "%s-abi.jar");

    transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JvmLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JvmLibrary, Path> get() {
            ImmutableSetMultimap.Builder<JvmLibrary, Path> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJar.this, getResolver().deprecatedGetPath(getBinaryJar()));
            classpathEntries.putAll(Classpaths.getClasspathEntries(
                    PrebuiltJar.this.getDeclaredDeps()));
            return classpathEntries.build();
          }
        });

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSet<JvmLibrary>>() {
              @Override
              public ImmutableSet<JvmLibrary> get() {
                return ImmutableSet.<JvmLibrary>builder()
                    .add(PrebuiltJar.this)
                    .addAll(Classpaths.getClasspathDeps(PrebuiltJar.this.getDeclaredDeps()))
                    .build();
              }
            });

    declaredClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JvmLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JvmLibrary, Path> get() {
            ImmutableSetMultimap.Builder<JvmLibrary, Path> classpathEntries =
                ImmutableSetMultimap.builder();
            classpathEntries.put(PrebuiltJar.this, getResolver().deprecatedGetPath(getBinaryJar()));
            return classpathEntries.build();
          }
        });

    copiedBinaryJar = BuildTargets.getGenPath(getBuildTarget(), "%s.jar");

    buildOutputInitializer =
        new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  public Optional<SourcePath> getSourceJar() {
    return sourceJar;
  }

  public Optional<String> getJavadocUrl() {
    return javadocUrl;
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return Optional.of(abiJar);
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  public JvmLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return getDeps();
  }

  @Override
  public ImmutableSetMultimap<JvmLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<JvmLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JvmLibrary, Path> getDeclaredClasspathEntries() {
    return declaredClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSetMultimap<JvmLibrary, Path> getOutputClasspathEntries() {
    return ImmutableSetMultimap.<JvmLibrary, Path>of(this, getResolver().deprecatedGetPath(
            getBinaryJar()));
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return ImmutableSortedSet.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return getDeclaredDeps();
  }

  @Override
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Create a copy of the JAR in case it was generated by another rule.
    Path resolvedBinaryJar = getResolver().deprecatedGetPath(binaryJar);
    steps.add(new MkdirStep(getProjectFilesystem(), copiedBinaryJar.getParent()));
    steps.add(CopyStep.forFile(getProjectFilesystem(), resolvedBinaryJar, copiedBinaryJar));
    buildableContext.recordArtifact(copiedBinaryJar);

    // Create a step to compute the ABI key.
    steps.add(new MkdirStep(getProjectFilesystem(), internalAbiJar.getParent()));
    steps.add(new RmStep(getProjectFilesystem(), internalAbiJar, true));
    steps.add(
        new CalculateAbiStep(
            buildableContext,
            getProjectFilesystem(),
            resolvedBinaryJar,
            internalAbiJar));

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    return steps.build();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getDeclaredDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addClasspathEntry(this, getBinaryJar());
    collector.addPathToThirdPartyJar(getBuildTarget(), getBinaryJar());
  }

  @Override
  public Path getPathToOutput() {
    return copiedBinaryJar;
  }

  public SourcePath getBinaryJar() {
    return new BuildTargetSourcePath(getBuildTarget());
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }
}
