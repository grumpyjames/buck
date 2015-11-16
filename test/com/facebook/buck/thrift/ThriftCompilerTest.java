/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.thrift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ThriftCompilerTest {

  private static final Tool DEFAULT_COMPILER =
      new CommandTool.Builder().addArg(new FakeSourcePath("thrift")).build();
  private static final ImmutableList<String> DEFAULT_FLAGS = ImmutableList.of("--allow-64-bits");
  private static final Path DEFAULT_OUTPUT_DIR = Paths.get("output-dir");
  private static final SourcePath DEFAULT_INPUT = new FakeSourcePath("test.thrift");
  private static final String DEFAULT_LANGUAGE = "cpp";
  private static final ImmutableSet<String> DEFAULT_OPTIONS = ImmutableSet.of("templates");
  private static final ImmutableList<Path> DEFAULT_INCLUDE_ROOTS =
      ImmutableList.of(Paths.get("blah-dir"));
  private static final ImmutableSet<Path> DEFAULT_HEADER_MAPS =
      ImmutableSet.of(Paths.get("some-header-map"));
  private static final ImmutableMap<Path, SourcePath> DEFAULT_INCLUDES =
      ImmutableMap.<Path, SourcePath>of(
          Paths.get("something.thrift"),
          new FakeSourcePath("blah/something.thrift"));
  private static final ImmutableSortedSet<String> DEFAULT_GENERATED_SOURCES =
      ImmutableSortedSet.of("source1", "source2");

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "blah/something.thrift", Strings.repeat("e", 40),
                    "different", Strings.repeat("c", 40),
                    "something.thrift", Strings.repeat("d", 40),
                    "thrift", Strings.repeat("a", 40),
                    "test.thrift", Strings.repeat("b", 40))),
            resolver);
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));

    // Verify that changing the compiler causes a rulekey change.

    RuleKey compilerChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            new CommandTool.Builder().addArg(new FakeSourcePath("different")).build(),
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the flags causes a rulekey change.

    RuleKey flagsChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            ImmutableList.of("--different"),
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, flagsChange);

    // Verify that changing the flags causes a rulekey change.

    RuleKey outputDirChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            Paths.get("different-dir"),
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, outputDirChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey inputChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            new FakeSourcePath("different"),
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey languageChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            "different",
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, languageChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey optionsChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            ImmutableSet.of("different"),
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, optionsChange);

    // Verify that changing the includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.

    RuleKey includeRootsChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            ImmutableList.of(Paths.get("different")),
            DEFAULT_HEADER_MAPS,
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertEquals(defaultRuleKey, includeRootsChange);

    // Verify that changing the header maps does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.

    RuleKey headerMapKeyChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            ImmutableSet.of(Paths.get("different-header-map")),
            DEFAULT_INCLUDES,
            DEFAULT_GENERATED_SOURCES));
    assertEquals(defaultRuleKey, headerMapKeyChange);

    // Verify that changing the name of the include causes a rulekey change.

    RuleKey includesKeyChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            ImmutableMap.<Path, SourcePath>of(
                DEFAULT_INCLUDES.entrySet().iterator().next().getKey(),
                new FakeSourcePath("different")),
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, includesKeyChange);

    // Verify that changing the contents of an include causes a rulekey change.

    RuleKey includesValueChange = ruleKeyBuilderFactory.build(
        new ThriftCompiler(
            params,
            resolver,
            DEFAULT_COMPILER,
            DEFAULT_FLAGS,
            DEFAULT_OUTPUT_DIR,
            DEFAULT_INPUT,
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            DEFAULT_INCLUDE_ROOTS,
            DEFAULT_HEADER_MAPS,
            ImmutableMap.of(
                Paths.get("different"),
                DEFAULT_INCLUDES.entrySet().iterator().next().getValue()),
            DEFAULT_GENERATED_SOURCES));
    assertNotEquals(defaultRuleKey, includesValueChange);
  }

  @Test
  public void thatCorrectBuildStepsAreUsed() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    ThriftCompiler thriftCompiler = new ThriftCompiler(
        params,
        pathResolver,
        DEFAULT_COMPILER,
        DEFAULT_FLAGS,
        DEFAULT_OUTPUT_DIR,
        DEFAULT_INPUT,
        DEFAULT_LANGUAGE,
        DEFAULT_OPTIONS,
        DEFAULT_INCLUDE_ROOTS,
        DEFAULT_HEADER_MAPS,
        DEFAULT_INCLUDES,
        DEFAULT_GENERATED_SOURCES);

    ImmutableList<Step> expected = ImmutableList.of(
        new MakeCleanDirectoryStep(params.getProjectFilesystem(), DEFAULT_OUTPUT_DIR),
        new ThriftCompilerStep(
            params.getProjectFilesystem().getRootPath(),
            ImmutableList.<String>builder()
                .addAll(DEFAULT_COMPILER.getCommandPrefix(pathResolver))
                .addAll(DEFAULT_FLAGS)
                .build(),
            DEFAULT_OUTPUT_DIR,
            pathResolver.deprecatedGetPath(DEFAULT_INPUT),
            DEFAULT_LANGUAGE,
            DEFAULT_OPTIONS,
            ImmutableList.<Path>builder()
                .addAll(DEFAULT_HEADER_MAPS)
                .addAll(DEFAULT_INCLUDE_ROOTS)
                .build()));
    ImmutableList<Step> actual = thriftCompiler.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    assertEquals(expected, actual);
  }

}
