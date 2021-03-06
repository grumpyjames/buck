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
package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceTypes;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.NativeLinkables;
import com.facebook.buck.cxx.Preprocessor;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.EnvironmentVariableMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.environment.Platform;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class NdkLibraryDescription implements Description<NdkLibraryDescription.Arg> {

  private static final Pattern EXTENSIONS_REGEX =
      Pattern.compile(
              ".*\\." +
              MoreStrings.regexPatternForAny("mk", "h", "hpp", "c", "cpp", "cc", "cxx") + "$");

  public static final MacroHandler MACRO_HANDLER = new MacroHandler(
      ImmutableMap.of(
          "env", new EnvironmentVariableMacroExpander(Platform.detect())
      )
  );

  private final Optional<String> ndkVersion;
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms;

  public NdkLibraryDescription(
      Optional<String> ndkVersion,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> cxxPlatforms) {
    this.ndkVersion = ndkVersion;
    this.cxxPlatforms = Preconditions.checkNotNull(cxxPlatforms);
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private Iterable<String> escapeForMakefile(ProjectFilesystem filesystem, Iterable<String> args) {
    ImmutableList.Builder<String> escapedArgs = ImmutableList.builder();

    for (String arg : args) {
      String escapedArg = arg;

      // The ndk-build makefiles make heavy use of the "eval" function to propagate variables,
      // which means we need to perform additional makefile escaping for *every* "eval" that
      // gets used.  Turns out there are three "evals", so we escape a total of four times
      // including the initial escaping.  Since the makefiles eventually hand-off these values
      // to the shell, we first perform bash escaping.
      //
      escapedArg = Escaper.escapeAsShellString(escapedArg);
      for (int i = 0; i < 4; i++) {
        escapedArg = Escaper.escapeAsMakefileValueString(escapedArg);
      }

      // We run ndk-build from the root of the NDK, so fixup paths that use the relative path to
      // the buck out directory.
      if (arg.startsWith(filesystem.getBuckPaths().getBuckOut().toString())) {
        escapedArg = "$(BUCK_PROJECT_DIR)/" + escapedArg;
      }

      escapedArgs.add(escapedArg);
    }

    return escapedArgs.build();
  }

  private String getTargetArchAbi(NdkCxxPlatforms.TargetCpuType cpuType) {
    switch (cpuType) {
      case ARM:
        return "armeabi";
      case ARMV7:
        return "armeabi-v7a";
      case ARM64:
        return "arm64-v8a";
      case X86:
        return "x86";
      case X86_64:
        return "x86_64";
      case MIPS:
        return "mips";
      default:
        throw new IllegalStateException();
    }
  }

  @VisibleForTesting
  protected static Path getGeneratedMakefilePath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "Android.%s.mk");
  }

  /**
   * @return a {@link BuildRule} which generates a Android.mk which pulls in the local Android.mk
   *     file and also appends relevant preprocessor and linker flags to use C/C++ library deps.
   */
  private Pair<String, Iterable<BuildRule>> generateMakefile(
      BuildRuleParams params,
      BuildRuleResolver resolver) throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    ImmutableList.Builder<String> outputLinesBuilder = ImmutableList.builder();
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();

    for (Map.Entry<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> entry : cxxPlatforms.entrySet()) {
      CxxPlatform cxxPlatform = entry.getValue().getCxxPlatform();

      // Collect the preprocessor input for all C/C++ library deps.  We search *through* other
      // NDK library rules.
      CxxPreprocessorInput cxxPreprocessorInput =
          CxxPreprocessorInput.concat(
              CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                  cxxPlatform,
                  params.getDeps(),
                  NdkLibrary.class::isInstance));

      // We add any dependencies from the C/C++ preprocessor input to this rule, even though
      // it technically should be added to the top-level rule.
      deps.addAll(cxxPreprocessorInput.getDeps(resolver, ruleFinder));

      // Add in the transitive preprocessor flags contributed by C/C++ library rules into the
      // NDK build.
      ImmutableList.Builder<String> ppFlags = ImmutableList.builder();
      ppFlags.addAll(cxxPreprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C));
      Preprocessor preprocessor =
          CxxSourceTypes.getPreprocessor(cxxPlatform, CxxSource.Type.C).resolve(resolver);
      ppFlags.addAll(
          CxxHeaders.getArgs(
              cxxPreprocessorInput.getIncludes(),
              pathResolver,
              Optional.empty(),
              preprocessor));
      String localCflags =
          Joiner.on(' ').join(escapeForMakefile(params.getProjectFilesystem(), ppFlags.build()));

      // Collect the native linkable input for all C/C++ library deps.  We search *through* other
      // NDK library rules.
      NativeLinkableInput nativeLinkableInput =
          NativeLinkables.getTransitiveNativeLinkableInput(
              cxxPlatform,
              params.getDeps(),
              Linker.LinkableDepType.SHARED,
              NdkLibrary.class::isInstance);

      // We add any dependencies from the native linkable input to this rule, even though
      // it technically should be added to the top-level rule.
      deps.addAll(
          nativeLinkableInput.getArgs().stream()
              .flatMap(arg -> arg.getDeps(ruleFinder).stream())
              .iterator());

      // Add in the transitive native linkable flags contributed by C/C++ library rules into the
      // NDK build.
      String localLdflags =
          Joiner.on(' ').join(
              escapeForMakefile(
                  params.getProjectFilesystem(),
                  com.facebook.buck.rules.args.Arg.stringify(
                      nativeLinkableInput.getArgs(),
                      pathResolver)));

      // Write the relevant lines to the generated makefile.
      if (!localCflags.isEmpty() || !localLdflags.isEmpty()) {
        NdkCxxPlatforms.TargetCpuType targetCpuType = entry.getKey();
        String targetArchAbi = getTargetArchAbi(targetCpuType);

        outputLinesBuilder.add(String.format("ifeq ($(TARGET_ARCH_ABI),%s)", targetArchAbi));
        if (!localCflags.isEmpty()) {
          outputLinesBuilder.add("BUCK_DEP_CFLAGS=" + localCflags);
        }
        if (!localLdflags.isEmpty()) {
          outputLinesBuilder.add("BUCK_DEP_LDFLAGS=" + localLdflags);
        }
        outputLinesBuilder.add("endif");
        outputLinesBuilder.add("");
      }
    }

    // GCC-only magic that rewrites non-deterministic parts of builds
    String ndksubst = NdkCxxPlatforms.ANDROID_NDK_ROOT;

    outputLinesBuilder.addAll(
        ImmutableList.copyOf(new String[] {
              // We're evaluated once per architecture, but want to add the cflags only once.
              "ifeq ($(BUCK_ALREADY_HOOKED_CFLAGS),)",
              "BUCK_ALREADY_HOOKED_CFLAGS := 1",
              // Only GCC supports -fdebug-prefix-map
              "ifeq ($(filter clang%,$(NDK_TOOLCHAIN_VERSION)),)",
              // Replace absolute paths with machine-relative ones.
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(NDK_ROOT)/=" + ndksubst + "/",
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(abspath $(BUCK_PROJECT_DIR))/=./",
              // Replace paths relative to the build rule with paths relative to the
              // repository root.
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(BUCK_PROJECT_DIR)/=./",
              "NDK_APP_CFLAGS += -fdebug-prefix-map=./=" +
              ".$(subst $(abspath $(BUCK_PROJECT_DIR)),,$(abspath $(CURDIR)))/",
              "NDK_APP_CFLAGS += -fno-record-gcc-switches",
              "ifeq ($(filter 4.6,$(TOOLCHAIN_VERSION)),)",
              // Do not let header canonicalization undo the work we just did above.  Note that GCC
              // 4.6 doesn't support this option, but that's okay, because it doesn't canonicalize
              // headers either.
              "NDK_APP_CPPFLAGS += -fno-canonical-system-headers",
              // If we include the -fdebug-prefix-map in the switches, the "from"-parts of which
              // contain machine-specific paths, we lose determinism.  GCC 4.6 didn't include
              // detailed command line argument information anyway.
              "NDK_APP_CFLAGS += -gno-record-gcc-switches",
              "endif", // !GCC 4.6
              "endif", // !clang

              // Rewrite NDK module paths to import managed modules by relative path instead of by
              // absolute path, but only for modules under the project root.
              "BUCK_SAVED_IMPORTS := $(__ndk_import_dirs)",
              "__ndk_import_dirs :=",
              "$(foreach __dir,$(BUCK_SAVED_IMPORTS),\\",
              "$(call import-add-path-optional,\\",
              "$(if $(filter $(abspath $(BUCK_PROJECT_DIR))%,$(__dir)),\\",
              "$(BUCK_PROJECT_DIR)$(patsubst $(abspath $(BUCK_PROJECT_DIR))%,%,$(__dir)),\\",
              "$(__dir))))",
              "endif", // !already hooked
              // Now add a toolchain directory to replace.  GCC's debug path replacement evaluates
              // candidate replaces last-first (because it internally pushes them all onto a stack
              // and scans the stack first-match-wins), so only add them after the more
              // generic paths.
              "NDK_APP_CFLAGS += -fdebug-prefix-map=$(TOOLCHAIN_PREBUILT_ROOT)/=" +
              "@ANDROID_NDK_ROOT@/toolchains/$(TOOLCHAIN_NAME)/prebuilt/@BUILD_HOST@/",
            }));

    outputLinesBuilder.add("include Android.mk");

    String contents = Joiner.on(System.lineSeparator()).join(outputLinesBuilder.build());

    return new Pair<String, Iterable<BuildRule>>(contents, deps.build());
  }

  @VisibleForTesting
  protected ImmutableSortedSet<SourcePath> findSources(
      final ProjectFilesystem filesystem,
      final Path buildRulePath) {
    final ImmutableSortedSet.Builder<SourcePath> srcs = ImmutableSortedSet.naturalOrder();

    try {
      final Path rootDirectory = filesystem.resolve(buildRulePath);
      Files.walkFileTree(
          rootDirectory,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          /* maxDepth */ Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (EXTENSIONS_REGEX.matcher(file.toString()).matches()) {
                srcs.add(
                    new PathSourcePath(
                        filesystem,
                        buildRulePath.resolve(rootDirectory.relativize(file))));
              }

              return super.visitFile(file, attrs);
            }
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return srcs.build();
  }

  @Override
  public <A extends Arg> NdkLibrary createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    Pair<String, Iterable<BuildRule>> makefilePair = generateMakefile(params, resolver);

    ImmutableSortedSet<SourcePath> sources;
    if (!args.srcs.isEmpty()) {
      sources = args.srcs;
    } else {
      sources = findSources(
          params.getProjectFilesystem(),
          params.getBuildTarget().getBasePath());
    }
    return new NdkLibrary(
        params.copyAppendingExtraDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(makefilePair.getSecond())
                .build()),
        getGeneratedMakefilePath(params.getBuildTarget(), params.getProjectFilesystem()),
        makefilePair.getFirst(),
        sources,
        args.flags,
        args.isAsset.orElse(false),
        ndkVersion,
        MACRO_HANDLER.getExpander(
            params.getBuildTarget(),
            cellRoots,
            resolver));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableList<String> flags = ImmutableList.of();
    public Optional<Boolean> isAsset;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  }

}
