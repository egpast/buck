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

package com.facebook.buck.core.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.graph.transformation.impl.GraphComputationStage;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.console.TestEventConsole;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.watchman.FileSystemNotWatchedException;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanClient;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanNotFoundException;
import com.facebook.buck.io.watchman.WatchmanQuery;
import com.facebook.buck.io.watchman.WatchmanQueryFailedException;
import com.facebook.buck.io.watchman.WatchmanQueryResp;
import com.facebook.buck.io.watchman.WatchmanQueryTimedOutException;
import com.facebook.buck.io.watchman.WatchmanTestDaemon;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import junitparams.JUnitParamsRunner;
import org.hamcrest.core.IsInstanceOf;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class WatchmanBuildPackageComputationTest extends AbstractBuildPackageComputationTest {

  private static final Logger LOG = Logger.get(WatchmanBuildPackageComputationTest.class);
  public static final Duration WATCHMAN_TIME_OUT = Duration.ofSeconds(30);

  @Rule public TemporaryPaths watchmanStateDirectory = new TemporaryPaths();

  private Clock clock;
  private WatchmanTestDaemon watchmanDaemon;

  @Before
  public void setUpWatchman() throws IOException, InterruptedException {
    clock = new DefaultClock();

    try {
      watchmanDaemon =
          WatchmanTestDaemon.start(
              watchmanStateDirectory.getRoot(), new ListeningProcessExecutor());
    } catch (WatchmanNotFoundException e) {
      Assume.assumeNoException(e);
    }
  }

  @After
  public void tearDownWatchman() throws IOException {
    if (watchmanDaemon != null) {
      watchmanDaemon.close();
    }
  }

  @Test
  public void findsBuildFilesInWatchmanProject() throws Exception {
    filesystem.mkdirs(Paths.get("project/dir"));
    filesystem.createNewFile(Paths.get("project/dir/BUCK"));

    try (WatchmanClient client = createWatchmanClient()) {
      long watchTimeoutNanos = TimeUnit.SECONDS.toNanos(5);
      long warnTimeoutNanos = TimeUnit.SECONDS.toNanos(1);
      client.queryWithTimeout(
          watchTimeoutNanos,
          warnTimeoutNanos,
          WatchmanQuery.watch(filesystem.getRootPath().toString()));
    }
    ProjectFilesystemView projectFilesystemView =
        filesystem.asView().withView(Paths.get("project"), ImmutableSet.of());
    ImmutableSet<AbsPath> watchedProjects = ImmutableSet.of(filesystem.resolve("project"));
    BuildPackagePaths paths =
        transform(
            key(CanonicalCellName.rootCell(), BuildTargetPattern.Kind.PACKAGE, "dir", ""),
            getComputationStages("BUCK", projectFilesystemView, watchedProjects));

    assertEquals(ImmutableSortedSet.of(Paths.get("dir")), paths.getPackageRoots());
  }

  @Test
  public void fileSystemMustBeWatchedByWatchman() throws IOException {
    filesystem.mkdirs(Paths.get("project"));

    ProjectFilesystemView projectFilesystemView =
        filesystem.asView().withView(Paths.get("project"), ImmutableSet.of());
    ImmutableSet<AbsPath> watchedProjects = ImmutableSet.of(filesystem.getRootPath());

    thrown.expect(IsInstanceOf.instanceOf(FileSystemNotWatchedException.class));
    thrown.expectMessage(
        String.format(
            "Path [%s] is not watched. The list of watched project: [[%s]]",
            projectFilesystemView.getRootPath(), filesystem.getRootPath()));
    getComputationStages("BUCK", projectFilesystemView, watchedProjects);
  }

  @Test
  public void watchmanMustNotBeNullWatchman() throws IOException {
    filesystem.mkdirs(Paths.get("project"));

    thrown.expect(IsInstanceOf.instanceOf(FileSystemNotWatchedException.class));

    thrown.expectMessage(
        String.format(
            "Path [%s] is not watched. The list of watched project: [%s]",
            filesystem.getRootPath(), ImmutableList.of()));

    new WatchmanBuildPackageComputation(
        "BUCK", filesystem.asView(), new WatchmanFactory.NullWatchman("test"), WATCHMAN_TIME_OUT);
  }

  @Test
  public void throwsIfWatchmanTimesOut() throws ExecutionException, InterruptedException {
    Watchman stubWatchmanFactory =
        createMockWatchmanFactory(
            (long timeoutNanos, long warnTimeoutNanos, WatchmanQuery<?> query) ->
                Either.ofRight(WatchmanClient.Timeout.INSTANCE));

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(WatchmanQueryTimedOutException.class));
    transform(
        key(CanonicalCellName.rootCell(), BuildTargetPattern.Kind.PACKAGE, "", ""),
        getComputationStages("BUCK", filesystem.asView(), stubWatchmanFactory));
  }

  @Test
  public void throwsIfWatchmanQueryFails() throws ExecutionException, InterruptedException {
    Watchman stubWatchmanFactory =
        createMockWatchmanFactory(
            (long timeoutNanos, long warnTimeoutNanos, WatchmanQuery<?> query) -> {
              LOG.info("Processing query: %s", query);
              if (query instanceof WatchmanQuery.Query) {
                throw new WatchmanQueryFailedException(
                    String.format(
                        "RootResolveError: unable to resolve root %s: directory %s not watched",
                        ((WatchmanQuery.Query) query).getPath(),
                        ((WatchmanQuery.Query) query).getPath()));
              } else {
                throw new RuntimeException("Watchman query not implemented");
              }
            });

    thrown.expect(ExecutionException.class);
    thrown.expectCause(IsInstanceOf.instanceOf(WatchmanQueryFailedException.class));
    transform(
        key(CanonicalCellName.rootCell(), BuildTargetPattern.Kind.PACKAGE, "", ""),
        getComputationStages("BUCK", filesystem.asView(), stubWatchmanFactory));
  }

  @Override
  protected ImmutableList<GraphComputationStage<?, ?>> getComputationStages(String buildFileName) {
    return getComputationStages(buildFileName, filesystem.asView(), ImmutableSet.of(tmp.getRoot()));
  }

  private ImmutableList<GraphComputationStage<?, ?>> getComputationStages(
      String buildFileName,
      ProjectFilesystemView filesystemView,
      ImmutableSet<AbsPath> watchedProjects) {
    Watchman watchman;
    try {
      watchman = createWatchmanClientFactory(watchedProjects);
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
    return getComputationStages(buildFileName, filesystemView, watchman);
  }

  private ImmutableList<GraphComputationStage<?, ?>> getComputationStages(
      String buildFileName, ProjectFilesystemView filesystemView, Watchman watchman) {
    return ImmutableList.of(
        new GraphComputationStage<>(
            new WatchmanBuildPackageComputation(
                buildFileName, filesystemView, watchman, WATCHMAN_TIME_OUT)));
  }

  private Watchman createWatchmanClientFactory(ImmutableSet<AbsPath> watchedProjects)
      throws IOException, InterruptedException {
    long connectTimeoutNanos = TimeUnit.SECONDS.toNanos(5);
    long endTimeNanos = clock.nanoTime() + connectTimeoutNanos;
    int syncTimeoutMillis = (int) TimeUnit.SECONDS.toMillis(60);
    return WatchmanFactory.getWatchman(
        createWatchmanClient(),
        watchmanDaemon.getTransportPath(),
        watchedProjects,
        new TestEventConsole(),
        clock,
        endTimeNanos,
        syncTimeoutMillis);
  }

  private WatchmanClient createWatchmanClient() throws IOException {
    return WatchmanFactory.createWatchmanClient(
        watchmanDaemon.getTransportPath(), new TestEventConsole(), clock);
  }

  MockWatchmanFactory createMockWatchmanFactory(QueryWithTimeoutFunction mockQueryWithTimeout) {
    return new MockWatchmanFactory() {
      @Override
      public WatchmanClient createClient() {
        return new WatchmanClient() {
          @Override
          public void close() {}

          @SuppressWarnings("unchecked")
          @Override
          public <R extends WatchmanQueryResp> Either<R, Timeout> queryWithTimeout(
              long timeoutNanos, long warnTimeNanos, WatchmanQuery<R> query)
              throws WatchmanQueryFailedException {
            return (Either<R, Timeout>)
                mockQueryWithTimeout.apply(timeoutNanos, warnTimeNanos, query);
          }
        };
      }
    };
  }

  abstract class MockWatchmanFactory extends Watchman {
    public MockWatchmanFactory() {
      super(
          ImmutableMap.of(
              tmp.getRoot(), ProjectWatch.of(tmp.getRoot().toString(), Optional.empty())),
          ImmutableSet.of(),
          ImmutableMap.of(),
          Optional.of(Paths.get("(MockWatchmanFactory socket)")),
          "");
    }
  }

  @FunctionalInterface
  interface QueryWithTimeoutFunction {
    Either<WatchmanQueryResp, WatchmanClient.Timeout> apply(
        long timeoutNanos, long warnTimeoutNanos, WatchmanQuery<?> query)
        throws WatchmanQueryFailedException;
  }
}
