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

package com.facebook.buck.jvm.java;

import com.facebook.buck.jvm.core.JvmLibrary;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExportDependencies;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;

import java.nio.file.Path;

public class JavaLibraryClasspathProvider {

  private JavaLibraryClasspathProvider() {
  }

  public static ImmutableSetMultimap<JvmLibrary, Path> getOutputClasspathEntries(
      JvmLibrary jvmLibraryRule,
      Optional<Path> outputJar) {
    ImmutableSetMultimap.Builder<JvmLibrary, Path> outputClasspathBuilder =
        ImmutableSetMultimap.builder();
    Iterable<JvmLibrary> javaExportedLibraryDeps;
    if (jvmLibraryRule instanceof ExportDependencies) {
      javaExportedLibraryDeps =
          getJavaLibraryDeps(((ExportDependencies) jvmLibraryRule).getExportedDeps());
    } else {
      javaExportedLibraryDeps = Sets.newHashSet();
    }

    for (JvmLibrary rule : javaExportedLibraryDeps) {
      outputClasspathBuilder.putAll(rule, rule.getOutputClasspathEntries().values());
      // If we have any exported deps, add an entry mapping ourselves to to their,
      // classpaths so when suggesting libraries to add we know that adding this library
      // would pull in it's deps.
      outputClasspathBuilder.putAll(
          jvmLibraryRule,
          rule.getOutputClasspathEntries().values());
    }

    if (outputJar.isPresent()) {
      outputClasspathBuilder.put(jvmLibraryRule, outputJar.get());
    }

    return outputClasspathBuilder.build();
  }

  public static ImmutableSetMultimap<JvmLibrary, Path> getTransitiveClasspathEntries(
      JvmLibrary jvmLibraryRule,
      Optional<Path> outputJar) {
    final ImmutableSetMultimap.Builder<JvmLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap<JvmLibrary, Path> classpathEntriesForDeps =
        Classpaths.getClasspathEntries(jvmLibraryRule.getDepsForTransitiveClasspathEntries());

    ImmutableSetMultimap<JvmLibrary, Path> classpathEntriesForExportedsDeps;
    if (jvmLibraryRule instanceof ExportDependencies) {
      classpathEntriesForExportedsDeps =
          Classpaths.getClasspathEntries(((ExportDependencies) jvmLibraryRule).getExportedDeps());
    } else {
      classpathEntriesForExportedsDeps = ImmutableSetMultimap.of();
    }

    classpathEntries.putAll(classpathEntriesForDeps);

    // If we have any exported deps, add an entry mapping ourselves to to their classpaths,
    // so when suggesting libraries to add we know that adding this library would pull in
    // it's deps.
    if (!classpathEntriesForExportedsDeps.isEmpty()) {
      classpathEntries.putAll(
          jvmLibraryRule,
          classpathEntriesForExportedsDeps.values());
    }

    // Only add ourselves to the classpath if there's a jar to be built.
    if (outputJar.isPresent()) {
      classpathEntries.put(jvmLibraryRule, outputJar.get());
    }

    return classpathEntries.build();
  }

  public static ImmutableSet<JvmLibrary> getTransitiveClasspathDeps(
      JvmLibrary jvmLibrary,
      Optional<Path> outputJar) {
    ImmutableSet.Builder<JvmLibrary> classpathDeps = ImmutableSet.builder();

    classpathDeps.addAll(
        Classpaths.getClasspathDeps(
            jvmLibrary.getDepsForTransitiveClasspathEntries()));

    // Only add ourselves to the classpath if there's a jar to be built.
    if (outputJar.isPresent()) {
      classpathDeps.add(jvmLibrary);
    }

    return classpathDeps.build();
  }

  public static ImmutableSetMultimap<JvmLibrary, Path> getDeclaredClasspathEntries(
      JvmLibrary jvmLibraryRule) {
    final ImmutableSetMultimap.Builder<JvmLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();

    Iterable<JvmLibrary> javaLibraryDeps = getJavaLibraryDeps(
        jvmLibraryRule.getDepsForTransitiveClasspathEntries());

    for (JvmLibrary rule : javaLibraryDeps) {
      classpathEntries.putAll(rule, rule.getOutputClasspathEntries().values());
    }
    return classpathEntries.build();
  }

  static FluentIterable<JvmLibrary> getJavaLibraryDeps(Iterable<BuildRule> deps) {
    return FluentIterable.from(deps).filter(JvmLibrary.class);
  }
}
