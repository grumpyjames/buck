/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.testutil;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.abi.source.FrontendOnlyJavacTask;
import com.google.common.base.Joiner;
import com.google.common.io.ByteStreams;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;

import org.hamcrest.Matchers;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.Processor;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * A {@link org.junit.Rule} for working with javac in tests.
 * <p>
 * Add it as a public field like this:
 * <p>
 * <pre>
 * &#64;Rule
 * public TestCompiler testCompiler = new TestCompiler();
 * </pre>
 */
public class TestCompiler extends ExternalResource implements AutoCloseable {
  private final TemporaryFolder inputFolder = new TemporaryFolder();
  private final TemporaryFolder outputFolder = new TemporaryFolder();
  private final Classes classes = new ClassesImpl(outputFolder);
  private final JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
  private final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
  private final StandardJavaFileManager fileManager =
      javaCompiler.getStandardFileManager(diagnostics, null, null);
  private final List<JavaFileObject> sourceFiles = new ArrayList<>();

  private TestCompiler classpathCompiler;
  private JavacTask javacTask;
  private boolean useFrontendOnlyJavacTask = false;
  private Set<String> classpath = new LinkedHashSet<>();

  public void addClasspathFileContents(String fileName, String contents) throws IOException {
    if (javacTask != null) {
      throw new AssertionError("Can't add contents after creating the task");
    }

    if (classpathCompiler == null) {
      classpathCompiler = new TestCompiler();
      try {
        classpathCompiler.before();
      } catch (Throwable throwable) {
        throw new AssertionError(throwable);
      }
    }
    classpathCompiler.addSourceFileContents(fileName, contents);
    classpath.add(classpathCompiler.getOutputDir());
  }

  public void addClasspath(Collection<Path> paths) {
    paths.stream()
        .map(Path::toString)
        .forEach(classpath::add);
  }

  public void addSourceFileContents(String fileName, String contents) throws IOException {
    addSourceFileLines(fileName, contents);
  }

  public void addSourceFileLines(String fileName, String... lines) throws IOException {
    if (javacTask != null) {
      throw new AssertionError("Can't add contents after creating the task");
    }
    Path sourceFilePath = inputFolder.getRoot().toPath().resolve(fileName);

    sourceFilePath.toFile().getParentFile().mkdirs();
    Files.write(sourceFilePath, Arrays.asList(lines), StandardCharsets.UTF_8);

    fileManager.getJavaFileObjects(sourceFilePath.toFile()).forEach(sourceFiles::add);
  }

  public void addSourceFile(Path file) throws IOException {
    Path outputFile = outputFolder.getRoot().toPath().resolve(file.getFileName());
    ByteStreams.copy(
        Files.newInputStream(file),
        Files.newOutputStream(outputFile));

    fileManager.getJavaFileObjects(outputFile.toFile()).forEach(sourceFiles::add);
  }

  public void useFrontendOnlyJavacTask() {
    if (javacTask != null) {
      throw new AssertionError("Can't change the task type after creating it");
    }

    this.useFrontendOnlyJavacTask = true;
  }

  public void setTaskListener(TaskListener taskListener) {
    getJavacTask().setTaskListener(taskListener);
  }

  public void setProcessors(List<Processor> processors) {
    getJavacTask().setProcessors(processors);
  }

  public Iterable<? extends CompilationUnitTree> parse() throws IOException {
    return getJavacTask().parse();
  }

  public Iterable<? extends TypeElement> enter() throws IOException {
    JavacTask javacTask = getJavacTask();

    try {
      @SuppressWarnings("unchecked")
      Iterable<? extends TypeElement> result = (Iterable<? extends TypeElement>)
          javacTask.getClass().getMethod("enter").invoke(javacTask);
      return result;
    } catch (IllegalAccessException | NoSuchMethodException e) {
      throw new AssertionError(e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }

      throw new AssertionError(e);
    }
  }

  public void compile() {
    assertTrue("Compilation encountered errors", getJavacTask().call());
  }

  public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
    return diagnostics.getDiagnostics();
  }

  public Classes getClasses() {
    return classes;
  }

  public Elements getElements() {
    return getJavacTask().getElements();
  }

  public Trees getTrees() {
    JavacTask javacTask = getJavacTask();
    if (javacTask instanceof FrontendOnlyJavacTask) {
      return ((FrontendOnlyJavacTask) javacTask).getTrees();
    }

    return Trees.instance(javacTask);
  }

  public Types getTypes() {
    return getJavacTask().getTypes();
  }

  public JavacTask getJavacTask() {
    if (javacTask == null) {
      compileClasspath();

      List<String> options = new ArrayList<>();
      options.add("-d");
      options.add(outputFolder.getRoot().toString());
      if (!classpath.isEmpty()) {
        options.add("-cp");
        options.add(Joiner.on(File.pathSeparatorChar).join(classpath));
      }

      javacTask = (JavacTask) javaCompiler.getTask(
          null,
          null,
          diagnostics,
          options,
          null,
          sourceFiles);

      if (useFrontendOnlyJavacTask) {
        javacTask = new FrontendOnlyJavacTask(javacTask);
      }
    }

    return javacTask;
  }

  private String getOutputDir() {
    return outputFolder.getRoot().toString();
  }

  private void compileClasspath() {
    if (classpathCompiler == null) {
      return;
    }

    classpathCompiler.compile();
    assertThat(classpathCompiler.getDiagnostics(), Matchers.empty());
  }

  public void init() {
    try {
      before();
    } catch (Throwable throwable) {
      throw new AssertionError(throwable);
    }
  }

  @Override
  protected void before() throws Throwable {
    inputFolder.create();
    outputFolder.create();
  }

  @Override
  protected void after() {
    if (classpathCompiler != null) {
      classpathCompiler.after();
    }
    outputFolder.delete();
    inputFolder.delete();
  }

  @Override
  public void close() {
    after();
  }
}
