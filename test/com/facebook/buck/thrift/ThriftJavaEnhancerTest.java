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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeExportDependenciesRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ThriftJavaEnhancerTest {

  private static final BuildTarget TARGET = BuildTargetFactory.newInstance("//:test#java");
  private static final BuildTarget JAVA_LIB_TARGET =
      BuildTargetFactory.newInstance("//java:library");
  private static final BuckConfig BUCK_CONFIG = FakeBuckConfig.builder()
      .setSections(
          ImmutableMap.of(
              "thrift", ImmutableMap.of("java_library", JAVA_LIB_TARGET.toString())))
      .build();
  private static final ThriftBuckConfig THRIFT_BUCK_CONFIG = new ThriftBuckConfig(BUCK_CONFIG);
  private static final ThriftJavaEnhancer ENHANCER = new ThriftJavaEnhancer(
      THRIFT_BUCK_CONFIG,
      DEFAULT_JAVAC_OPTIONS);

  private static FakeBuildRule createFakeBuildRule(
      String target,
      SourcePathResolver resolver,
      BuildRule... deps) {
    return new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target))
            .setDeclaredDeps(ImmutableSortedSet.copyOf(deps))
            .build(), resolver);
  }

  private static ThriftCompiler createFakeThriftCompiler(
      String target,
      SourcePathResolver resolver) {
    return new ThriftCompiler(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target)).build(),
        resolver,
        new CommandTool.Builder()
            .addArg(new FakeSourcePath("compiler"))
            .build(),
        ImmutableList.<String>of(),
        Paths.get("output"),
        new FakeSourcePath("source"),
        "language",
        ImmutableSet.<String>of(),
        ImmutableList.<Path>of(),
        ImmutableSet.<Path>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableSortedSet.<String>of());
  }

  @Test
  public void getLanguage() {
    assertEquals(
        "java",
        ENHANCER.getLanguage());
  }

  @Test
  public void getFlavor() {
    assertEquals(
        ImmutableFlavor.of("java"),
        ENHANCER.getFlavor());
  }

  @Test
  public void getOptions() {
    ThriftConstructorArg arg = new ThriftConstructorArg();
    ImmutableSet<String> options;

    // Test empty options.
    options = ImmutableSet.of();
    arg.javaOptions = Optional.of(options);
    assertEquals(
        options,
        ENHANCER.getOptions(TARGET, arg));

    // Test set options.
    options = ImmutableSet.of("test", "option");
    arg.javaOptions = Optional.of(options);
    assertEquals(
        options,
        ENHANCER.getOptions(TARGET, arg));

    // Test absent options.
    arg.javaOptions = Optional.absent();
    assertEquals(
        ImmutableSet.<String>of(),
        ENHANCER.getOptions(TARGET, arg));
  }

  @Test
  public void getImplicitDeps() {
    ThriftConstructorArg arg = new ThriftConstructorArg();

    // Verify that setting "thrift:java_library" in the buck config propagates that
    // dep via the getImplicitDeps method.
    assertEquals(
        ImmutableSet.of(JAVA_LIB_TARGET),
        ENHANCER.getImplicitDepsForTargetFromConstructorArg(TARGET, arg));
  }

  @Test
  public void createBuildRule() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRuleParams flavoredParams = new FakeBuildRuleParamsBuilder(TARGET).build();

    // Add a dummy dependency to the constructor arg to make sure it gets through.
    ThriftConstructorArg arg = new ThriftConstructorArg();

    // Setup up some thrift inputs to pass to the createBuildRule method.
    ImmutableMap<String, ThriftSource> sources = ImmutableMap.of(
        "test1.thrift", new ThriftSource(
            createFakeThriftCompiler("//:thrift_source1", pathResolver),
            ImmutableList.<String>of(),
            Paths.get("output1")),
        "test2.thrift", new ThriftSource(
            createFakeThriftCompiler("//:thrift_source2", pathResolver),
            ImmutableList.<String>of(),
            Paths.get("output2")));

    // Create a dummy implicit dep to pass in.
    ImmutableSortedSet<BuildRule> deps = ImmutableSortedSet.<BuildRule>of(
        createFakeBuildRule("//:dep", pathResolver));

    // Run the enhancer to create the language specific build rule.
    DefaultJavaLibrary library = ENHANCER
        .createBuildRule(TargetGraph.EMPTY, flavoredParams, resolver, arg, sources, deps);

    // Verify that the first thrift source created a source zip rule with correct deps.
    BuildRule srcZip1 = resolver.getRule(
        ENHANCER.getSourceZipBuildTarget(TARGET.getUnflavoredBuildTarget(), "test1.thrift"));
    assertNotNull(srcZip1);
    assertTrue(srcZip1 instanceof SrcZip);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(sources.get("test1.thrift").getCompileRule()),
        srcZip1.getDeps());

    // Verify that the second thrift source created a source zip rule with correct deps.
    BuildRule srcZip2 = resolver.getRule(
        ENHANCER.getSourceZipBuildTarget(TARGET.getUnflavoredBuildTarget(), "test2.thrift"));
    assertNotNull(srcZip2);
    assertTrue(srcZip2 instanceof SrcZip);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(sources.get("test2.thrift").getCompileRule()),
        srcZip2.getDeps());

    // Verify that the top-level default java lib has correct deps.
    assertEquals(
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(deps)
            .add(srcZip1)
            .add(srcZip2)
            .build(),
        library.getDeps());
  }

  @Test
  public void exportedDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRuleParams flavoredParams =
        new FakeBuildRuleParamsBuilder(TARGET).build();

    // Add a dummy dependency to the constructor arg to make sure it gets through.
    ThriftConstructorArg arg = new ThriftConstructorArg();

    // Setup up some thrift inputs to pass to the createBuildRule method.
    ImmutableMap<String, ThriftSource> sources = ImmutableMap.of(
        "test.thrift", new ThriftSource(
            createFakeThriftCompiler("//:thrift_source", pathResolver),
            ImmutableList.<String>of(),
            Paths.get("output")));

    // Create a dep chain with an exported dep.
    FakeBuildRule exportedRule =
        resolver.addToIndex(new FakeBuildRule("//:exported_rule", pathResolver));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    // Run the enhancer to create the language specific build rule.
    DefaultJavaLibrary library = ENHANCER
        .createBuildRule(
            TargetGraph.EMPTY,
            flavoredParams,
            resolver,
            arg,
            sources,
            ImmutableSortedSet.<BuildRule>of(exportingRule));

    assertThat(library.getDeps(), Matchers.<BuildRule>hasItem(exportedRule));
  }

}
