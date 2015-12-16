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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import javax.annotation.processing.Processor;
import javax.tools.JavaCompiler;

class ProcessorBundle implements Closeable {

  private URLClassLoader singletonClassLoader;

  private List<Processor> processors = Lists.newArrayList();

  @Override
  public void close() throws IOException {
    // no-op due to checker framework thread-safety issues
  }

  public void initialiseClassLoader(final URL[] urls, final ClassLoader compilerClassLoader) {
    if (singletonClassLoader == null) {
      singletonClassLoader = new URLClassLoader(urls, compilerClassLoader);
    }
  }

  public Class<? extends Processor> loadClass(
      final String name,
      final Class<Processor> processorClass) throws ClassNotFoundException {

    return Preconditions.checkNotNull(this.singletonClassLoader)
        .loadClass(name)
        .asSubclass(processorClass);

  }

  public void applyToCompilationTask(JavaCompiler.CompilationTask compilationTask) {
    compilationTask.setProcessors(this.processors);
  }

  public void addProcessor(final Processor processor) {
    processors.add(processor);
  }
}
