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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RobolectricTestRuleTest {

  private class ResourceRule implements HasAndroidResourceDeps {
    private final SourcePath resourceDirectory;

    public ResourceRule(SourcePath resourceDirectory) {
      this.resourceDirectory = resourceDirectory;
    }

    @Override
    public Path getPathToTextSymbolsFile() {
      return null;
    }

    @Override
    public Sha1HashCode getTextSymbolsAbiKey() {
      return null;
    }

    @Override
    public String getRDotJavaPackage() {
      return null;
    }

    @Override
    public SourcePath getRes() {
      return resourceDirectory;
    }

    @Override
    public SourcePath getAssets() {
      return null;
    }

    @Override
    public BuildTarget getBuildTarget() {
      return null;
    }
  }

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testRobolectricContainsAllResourceDependenciesInResVmArg() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    ImmutableList.Builder<HasAndroidResourceDeps> resDepsBuilder =
        ImmutableList.builder();
    for (int i = 0; i < 10; i++) {
      String path = "java/src/com/facebook/base/" + i + "/res";
      filesystem.mkdirs(Paths.get(path).resolve("values"));
      resDepsBuilder.add(
          new ResourceRule(new FakeSourcePath(path)));
    }
    ImmutableList<HasAndroidResourceDeps> resDeps = resDepsBuilder.build();

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    RobolectricTest robolectricTest = (RobolectricTest) RobolectricTestBuilder
        .createBuilder(robolectricBuildTarget)
        .build(resolver, filesystem);

    String result = robolectricTest.getRobolectricResourceDirectories(resDeps);
    for (HasAndroidResourceDeps dep : resDeps) {
      // Every value should be a PathSourcePath
      assertTrue(
          result + " does not contain " + dep.getRes(),
          result.contains(((PathSourcePath) dep.getRes()).getRelativePath().toString()));
    }
  }

  @Test
  public void testRobolectricResourceDependenciesVmArgHasCorrectFormat() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());
    filesystem.mkdirs(Paths.get("res1/values"));
    filesystem.mkdirs(Paths.get("res2/values"));
    filesystem.mkdirs(Paths.get("res3/values"));
    filesystem.mkdirs(Paths.get("res4_to_ignore"));

    Path resDep1 = Paths.get("res1");
    Path resDep2 = Paths.get("res2");
    Path resDep3 = Paths.get("res3");
    Path resDep4 = Paths.get("res4_to_ignore");

    StringBuilder expectedVmArgBuilder = new StringBuilder();
    expectedVmArgBuilder.append("-D")
        .append(RobolectricTest.LIST_OF_RESOURCE_DIRECTORIES_PROPERTY_NAME)
        .append("=")
        .append(resDep1)
        .append(File.pathSeparator)
        .append(resDep2)
        .append(File.pathSeparator)
        .append(resDep3);

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");

    RobolectricTest robolectricTest = (RobolectricTest) RobolectricTestBuilder
        .createBuilder(robolectricBuildTarget)
        .build(resolver, filesystem);

    String result = robolectricTest.getRobolectricResourceDirectories(
        ImmutableList.<HasAndroidResourceDeps>of(
            new ResourceRule(new PathSourcePath(filesystem, resDep1)),
            new ResourceRule(new PathSourcePath(filesystem, resDep2)),
            new ResourceRule(new PathSourcePath(filesystem, resDep3)),
            new ResourceRule(new PathSourcePath(filesystem, resDep4))));

    assertEquals(expectedVmArgBuilder.toString(), result);
  }

  @Test
  public void testRobolectricThrowsIfResourceDirNotThere() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");
    RobolectricTest robolectricTest = (RobolectricTest) RobolectricTestBuilder
        .createBuilder(robolectricBuildTarget)
        .build(resolver, filesystem);

    try {
      robolectricTest.getRobolectricResourceDirectories(
          ImmutableList.<HasAndroidResourceDeps>of(
              new ResourceRule(new PathSourcePath(filesystem, Paths.get("not_there")))));
      fail("Expected FileNotFoundException");
    } catch (RuntimeException e) {
      assertThat(e.getMessage(), Matchers.containsString("not_there"));
    }
  }

  @Test
  public void runtimeDepsIncludeTransitiveResources() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem(temporaryFolder.getRoot());

    BuildTarget genRuleTarget = BuildTargetFactory.newInstance("//:gen");
    BuildRule genRule = GenruleBuilder.newGenruleBuilder(genRuleTarget)
        .setOut("out")
        .build(resolver);

    BuildTarget res2RuleTarget = BuildTargetFactory.newInstance("//:res2");
    AndroidResourceBuilder.createBuilder(res2RuleTarget)
        .setRes(new BuildTargetSourcePath(genRuleTarget))
        .setRDotJavaPackage("foo.bar")
        .build(resolver);

    BuildTarget robolectricBuildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base/robolectricTest:robolectricTest");
    RobolectricTest robolectricTest = (RobolectricTest) RobolectricTestBuilder
        .createBuilder(robolectricBuildTarget)
        .addDep(res2RuleTarget)
        .build(resolver, filesystem);

    assertThat(robolectricTest.getRuntimeDeps(), Matchers.hasItem(genRule));
  }
}
