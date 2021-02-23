/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.MiniAapt;
import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathDeps;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * An object that represents the resources of an android library.
 *
 * <p>Suppose this were a rule defined in <code>src/com/facebook/feed/BUCK</code>:
 *
 * <pre>
 * android_resources(
 *   name = 'res',
 *   res = 'res',
 *   assets = 'buck-assets',
 *   deps = [
 *     '//first-party/orca/lib-ui:lib-ui',
 *   ],
 * )
 * </pre>
 */
public class AndroidResource extends ModernBuildRule<AndroidResource.Impl>
    implements AndroidPackageable,
        HasAndroidResourceDeps,
        HasClasspathDeps,
        InitializableFromDisk<String> {

  private final BuildOutputInitializer<String> buildOutputInitializer;
  private final ImmutableSortedSet<BuildRule> deps;

  public AndroidResource(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<BuildRule> deps,
      @Nullable SourcePath res,
      ImmutableSortedMap<Path, SourcePath> resSrcs,
      @Nullable String rDotJavaPackageArgument,
      @Nullable SourcePath assets,
      ImmutableSortedMap<Path, SourcePath> assetsSrcs,
      @Nullable SourcePath manifestFile,
      Supplier<ImmutableSortedSet<? extends SourcePath>> symbolFilesFromDeps,
      boolean hasWhitelistedStrings,
      boolean isVerifyingXmlAttrsEnabled) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            buildTarget,
            res,
            MoreMaps.transformKeysAndSort(resSrcs, Path::toString),
            assets,
            MoreMaps.transformKeysAndSort(assetsSrcs, Path::toString),
            manifestFile,
            symbolFilesFromDeps,
            hasWhitelistedStrings,
            isVerifyingXmlAttrsEnabled,
            rDotJavaPackageArgument));

    if (res != null && rDotJavaPackageArgument == null && manifestFile == null) {
      throw new HumanReadableException(
          "When the 'res' is specified for android_resource() %s, at least one of 'package' or "
              + "'manifest' must be specified.",
          buildTarget);
    }

    this.deps = deps;
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  public AndroidResource(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<BuildRule> deps,
      @Nullable SourcePath res,
      ImmutableSortedMap<Path, SourcePath> resSrcs,
      @Nullable String rDotJavaPackageArgument,
      @Nullable SourcePath assets,
      ImmutableSortedMap<Path, SourcePath> assetsSrcs,
      @Nullable SourcePath manifestFile,
      boolean hasWhitelistedStrings) {
    this(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        deps,
        res,
        resSrcs,
        rDotJavaPackageArgument,
        assets,
        assetsSrcs,
        manifestFile,
        hasWhitelistedStrings,
        /* isVerifyingXmlAttrsEnabled */ false);
  }

  public AndroidResource(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableSortedSet<BuildRule> deps,
      @Nullable SourcePath res,
      ImmutableSortedMap<Path, SourcePath> resSrcs,
      @Nullable String rDotJavaPackageArgument,
      @Nullable SourcePath assets,
      ImmutableSortedMap<Path, SourcePath> assetsSrcs,
      @Nullable SourcePath manifestFile,
      boolean hasWhitelistedStrings,
      boolean isVerifyingXmlAttrsEnabled) {
    this(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        deps,
        res,
        resSrcs,
        rDotJavaPackageArgument,
        assets,
        assetsSrcs,
        manifestFile,
        () ->
            RichStream.from(getAndroidResourceDeps(deps))
                .filter(input -> input.getRes() != null)
                .map(HasAndroidResourceDeps::getPathToTextSymbolsFile)
                .toImmutableSortedSet(Ordering.natural()),
        hasWhitelistedStrings,
        isVerifyingXmlAttrsEnabled);
  }

  @Override
  @Nullable
  public SourcePath getRes() {
    return getBuildable().res;
  }

  @Override
  @Nullable
  public SourcePath getAssets() {
    return getBuildable().assets;
  }

  @Nullable
  public SourcePath getManifestFile() {
    return getBuildable().manifestFile;
  }

  static class Impl implements Buildable {
    @AddToRuleKey @Nullable private final SourcePath res;

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final ImmutableSortedMap<String, SourcePath> resSrcs;

    @AddToRuleKey @Nullable private final SourcePath assets;

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final ImmutableSortedMap<String, SourcePath> assetsSrcs;

    private final OutputPath pathToTextSymbolsDir;
    private final OutputPath pathToTextSymbolsFile;
    private final OutputPath pathToRDotJavaPackageFile;

    @AddToRuleKey @Nullable private final SourcePath manifestFile;

    @AddToRuleKey private final Supplier<ImmutableSortedSet<? extends SourcePath>> symbolsOfDeps;

    @AddToRuleKey private final boolean hasWhitelistedStrings;

    @AddToRuleKey private final boolean isVerifyingXmlAttrsEnabled;

    /** This is the original {@code package} argument passed to this rule. */
    @AddToRuleKey @Nullable private final String rDotJavaPackageArgument;

    /**
     * Supplier that returns the package for the Java class generated for the resources in {@link
     * #res}, if any. The value for this supplier is determined, as follows:
     *
     * <ul>
     *   <li>If the user specified a {@code package} argument, the supplier will return that value.
     *   <li>Failing that, when the rule is built, it will parse the package from the file specified
     *       by the {@code manifest} so that it can be returned by this supplier. (Note this also
     *       needs to work correctly if the rule is initialized from disk.)
     *   <li>In all other cases (e.g., both {@code package} and {@code manifest} are unspecified),
     *       the behavior is undefined.
     * </ul>
     */
    private final Supplier<String> rDotJavaPackageSupplier;

    private final AtomicReference<String> rDotJavaPackage;

    Impl(
        BuildTarget buildTarget,
        @Nullable SourcePath res,
        ImmutableSortedMap<String, SourcePath> resSrcs,
        @Nullable SourcePath assets,
        ImmutableSortedMap<String, SourcePath> assetsSrcs,
        @Nullable SourcePath manifestFile,
        Supplier<ImmutableSortedSet<? extends SourcePath>> symbolsOfDeps,
        boolean hasWhitelistedStrings,
        boolean isVerifyingXmlAttrsEnabled,
        @Nullable String rDotJavaPackageArgument) {
      this.res = res;
      this.resSrcs = resSrcs;
      this.assets = assets;
      this.assetsSrcs = assetsSrcs;
      this.manifestFile = manifestFile;
      this.symbolsOfDeps = symbolsOfDeps;
      this.hasWhitelistedStrings = hasWhitelistedStrings;
      this.isVerifyingXmlAttrsEnabled = isVerifyingXmlAttrsEnabled;

      this.pathToTextSymbolsDir =
          new OutputPath(String.format("__%s_text_symbols__", buildTarget.getShortName()));
      this.pathToTextSymbolsFile = pathToTextSymbolsDir.resolve("R.txt");
      this.pathToRDotJavaPackageFile = pathToTextSymbolsDir.resolve("RDotJavaPackage.txt");

      this.rDotJavaPackageArgument = rDotJavaPackageArgument;
      this.rDotJavaPackage = new AtomicReference<>(rDotJavaPackageArgument);

      this.rDotJavaPackageSupplier =
          () -> {
            String rDotJavaPackage1 = AndroidResource.Impl.this.rDotJavaPackage.get();
            if (rDotJavaPackage1 != null) {
              return rDotJavaPackage1;
            } else {
              throw new RuntimeException(
                  "rDotJavaPackage was requested before it was made available.");
            }
          };
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.of(
                  outputPathResolver.resolvePath(pathToTextSymbolsDir).getPath())));
      if (res == null) {
        return steps
            .add(new TouchStep(filesystem, outputPathResolver.resolvePath(pathToTextSymbolsFile)))
            .add(
                WriteFileStep.of(
                    filesystem.getRootPath(),
                    rDotJavaPackageArgument == null ? "" : rDotJavaPackageArgument,
                    outputPathResolver.resolvePath(pathToRDotJavaPackageFile),
                    false /* executable */))
            .build();
      }

      // If the 'package' was not specified for this android_resource(), then attempt to parse it
      // from the AndroidManifest.xml.
      if (rDotJavaPackageArgument == null) {
        Objects.requireNonNull(
            manifestFile,
            "manifestFile cannot be null when res is non-null and rDotJavaPackageArgument is "
                + "null. This should already be enforced by the constructor.");
        steps.add(
            new ExtractFromAndroidManifestStep(
                context.getSourcePathResolver().getAbsolutePath(manifestFile).getPath(),
                filesystem,
                Objects.requireNonNull(
                    outputPathResolver.resolvePath(pathToRDotJavaPackageFile).getPath())));
      } else {
        steps.add(
            WriteFileStep.of(
                filesystem.getRootPath(),
                rDotJavaPackageArgument,
                outputPathResolver.resolvePath(pathToRDotJavaPackageFile),
                false /* executable */));
      }

      ImmutableSet<Path> pathsToSymbolsOfDeps =
          symbolsOfDeps.get().stream()
              .map(
                  sourcePath ->
                      context.getSourcePathResolver().getAbsolutePath(sourcePath).getPath())
              .collect(ImmutableSet.toImmutableSet());
      steps.add(
          new MiniAapt(
              context.getSourcePathResolver(),
              filesystem,
              Objects.requireNonNull(res),
              Objects.requireNonNull(
                  outputPathResolver.resolvePath(pathToTextSymbolsFile).getPath()),
              pathsToSymbolsOfDeps,
              isVerifyingXmlAttrsEnabled));
      return steps.build();
    }
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().pathToTextSymbolsDir);
  }

  @Override
  public SourcePath getPathToTextSymbolsFile() {
    return getSourcePath(getBuildable().pathToTextSymbolsFile);
  }

  @Override
  public SourcePath getPathToRDotJavaPackageFile() {
    return getSourcePath(getBuildable().pathToRDotJavaPackageFile);
  }

  @Override
  public String getRDotJavaPackage() {
    String rDotJavaPackage = getBuildable().rDotJavaPackageSupplier.get();
    if (rDotJavaPackage == null) {
      throw new RuntimeException("No package for " + getBuildTarget());
    }
    return rDotJavaPackage;
  }

  @Override
  public String initializeFromDisk(SourcePathResolverAdapter pathResolver) {
    String rDotJavaPackageFromFile =
        getProjectFilesystem()
            .readFirstLine(
                pathResolver
                    .getRelativePath(
                        getProjectFilesystem(),
                        getSourcePath(getBuildable().pathToRDotJavaPackageFile))
                    .getPath())
            .get();
    if (getBuildable().rDotJavaPackageArgument != null
        && !rDotJavaPackageFromFile.equals(getBuildable().rDotJavaPackageArgument)) {
      throw new RuntimeException(
          String.format(
              "%s contains incorrect rDotJavaPackage (%s!=%s)",
              getBuildable().pathToRDotJavaPackageFile,
              rDotJavaPackageFromFile,
              getBuildable().rDotJavaPackageArgument));
    }
    getBuildable().rDotJavaPackage.set(rDotJavaPackageFromFile);
    return rDotJavaPackageFromFile;
  }

  @Override
  public BuildOutputInitializer<String> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(
      BuildRuleResolver ruleResolver, Supplier<Iterable<NdkCxxPlatform>> ndkCxxPlatforms) {
    return AndroidPackageableCollector.getPackageableRules(deps);
  }

  @Override
  public void addToCollector(
      ActionGraphBuilder graphBuilder, AndroidPackageableCollector collector) {
    SourcePath res = getBuildable().res;
    if (res != null) {
      if (getBuildable().hasWhitelistedStrings) {
        collector.addStringWhitelistedResourceDirectory(getBuildTarget(), res);
      } else {
        collector.addResourceDirectory(getBuildTarget(), res);
      }
    }

    SourcePath assets = getBuildable().assets;
    if (assets != null) {
      collector.addAssetsDirectory(getBuildTarget(), assets);
    }

    SourcePath manifestFile = getBuildable().manifestFile;
    if (manifestFile != null) {
      collector.addManifestPiece(getBuildTarget(), manifestFile);
    }

    String rDotJavaPackageArgument = getBuildable().rDotJavaPackageArgument;
    if (rDotJavaPackageArgument != null) {
      collector.addResourcePackage(getBuildTarget(), rDotJavaPackageArgument);
    }
  }

  @Override
  public Set<BuildRule> getDepsForTransitiveClasspathEntries() {
    return deps.stream()
        .filter(rule -> rule instanceof HasClasspathEntries)
        .collect(ImmutableSet.toImmutableSet());
  }

  public Set<BuildRule> getDeps() {
    return deps;
  }

  private static ImmutableSet<HasAndroidResourceDeps> getAndroidResourceDeps(Set<BuildRule> deps) {
    ImmutableSet.Builder<HasAndroidResourceDeps> buildRules = ImmutableSet.builder();
    for (BuildRule buildRule : deps) {
      if (buildRule instanceof ExportDependencies) {
        buildRules.addAll(
            getAndroidResourceDeps(((ExportDependencies) buildRule).getExportedDeps()));
      }

      if (buildRule instanceof HasAndroidResourceDeps) {
        buildRules.add((HasAndroidResourceDeps) buildRule);
      }
    }
    return buildRules.build();
  }
}
