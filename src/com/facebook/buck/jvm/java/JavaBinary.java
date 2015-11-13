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

import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.io.DirectoryTraverser;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

@BuildsAnnotationProcessor
public class JavaBinary extends AbstractBuildRule
    implements BinaryBuildRule, HasClasspathEntries, RuleKeyAppendable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(PACKAGING);

  @AddToRuleKey
  @Nullable
  private final String mainClass;

  @AddToRuleKey
  @Nullable
  private final SourcePath manifestFile;
  private final boolean mergeManifests;

  @Nullable
  private final Path metaInfDirectory;
  @AddToRuleKey
  private final ImmutableSet<String> blacklist;

  private final DirectoryTraverser directoryTraverser;
  private final ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries;

  public JavaBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      @Nullable String mainClass,
      @Nullable SourcePath manifestFile,
      boolean mergeManifests,
      @Nullable Path metaInfDirectory,
      ImmutableSet<String> blacklist,
      DirectoryTraverser directoryTraverser,
      ImmutableSetMultimap<JavaLibrary, Path> transitiveClasspathEntries) {
    super(params, resolver);
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.mergeManifests = mergeManifests;
    this.metaInfDirectory = metaInfDirectory;
    this.blacklist = blacklist;
    this.directoryTraverser = directoryTraverser;
    this.transitiveClasspathEntries = transitiveClasspathEntries;
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    // Build a sorted set so that metaInfDirectory contents are listed in a canonical order.
    ImmutableSortedSet.Builder<Path> paths = ImmutableSortedSet.naturalOrder();
    BuildRules.addInputsToSortedSet(metaInfDirectory, paths, directoryTraverser);

    return builder.setReflectively("metaInfDirectory", paths.build());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    Path outputDirectory = getOutputDirectory();
    Step mkdir = new MkdirStep(getProjectFilesystem(), outputDirectory);
    commands.add(mkdir);

    ImmutableSortedSet<Path> includePaths;
    if (metaInfDirectory != null) {
      Path stagingRoot = outputDirectory.resolve("meta_inf_staging");
      Path stagingTarget = stagingRoot.resolve("META-INF");

      MakeCleanDirectoryStep createStagingRoot = new MakeCleanDirectoryStep(
          getProjectFilesystem(),
          stagingRoot);
      commands.add(createStagingRoot);

      MkdirAndSymlinkFileStep link = new MkdirAndSymlinkFileStep(
          getProjectFilesystem(),
          metaInfDirectory,
          stagingTarget);
      commands.add(link);

      includePaths = ImmutableSortedSet.<Path>naturalOrder()
          .add(stagingRoot)
          .addAll(getTransitiveClasspathEntries().values())
          .build();
    } else {
      includePaths = ImmutableSortedSet.copyOf(getTransitiveClasspathEntries().values());
    }

    Path outputFile = getPathToOutput();
    Path manifestPath = manifestFile == null ? null : getResolver().deprecatedGetPath(manifestFile);
    Step jar = new JarDirectoryStep(
        getProjectFilesystem(),
        outputFile,
        includePaths,
        mainClass,
        manifestPath,
        mergeManifests,
        blacklist);
    commands.add(jar);

    buildableContext.recordArtifact(outputFile);
    return commands.build();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntries;
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathEntries.keySet();
  }

  private Path getOutputDirectory() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s").getParent();
  }

  @Override
  public Path getPathToOutput() {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputDirectory(),
            getBuildTarget().getShortNameAndFlavorPostfix()));
  }

  @Override
  public Tool getExecutableCommand() {
    Preconditions.checkState(
        mainClass != null,
        "Must specify a main class for %s in order to to run it.",
        getBuildTarget());

    return new CommandTool.Builder()
        .addArg("java")
        .addArg("-jar")
        .addArg(new BuildTargetSourcePath(getBuildTarget()))
        .build();
  }
}
