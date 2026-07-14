package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link EmbeddedFlowableHttpMockSupport}'s {@code retain}/{@code release}/{@code
 * markConfiguredOnce} are actually safe under concurrent access, not just single-threaded by the
 * external assumption its own Javadoc documents (Spring builds at most one {@code
 * ApplicationContext} at a time, so these only ever fire at context start/close). That assumption
 * holds today because Surefire runs test classes sequentially, but this is shared mutable state
 * inside a *testing* library -- a race here wouldn't fail loudly, it would make a *consumer's* test
 * suite flaky in a way that looks like their own bug, with nothing pointing back at this class.
 * This test locks the safety in directly, independent of that external assumption continuing to
 * hold.
 *
 * <p>Each test uses a service name unique to itself, pointed at the already-existing {@code
 * httpmocks/demo-service} mapping folder other tests also use, so its refcounts can't be polluted
 * by another test class's real, JVM-lifetime-cached state for a shared literal name.
 */
class EmbeddedFlowableHttpMockSupportConcurrencyTest {

  private static final int THREAD_COUNT = 32;

  @Test
  void concurrentRetainsShareExactlyOneServerInstance() throws Exception {
    final String name = "concurrency-single-start-service";
    final String location = "httpmocks/demo-service";

    final List<WireMockServer> returned =
        runConcurrently(THREAD_COUNT, () -> EmbeddedFlowableHttpMockSupport.retain(name, location));

    assertThat(new HashSet<>(returned)).hasSize(1);
    assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isEqualTo(THREAD_COUNT);

    releaseConcurrently(THREAD_COUNT, name, location);
    assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isZero();
    assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, location)).isFalse();
  }

  @Test
  void concurrentRetainAndReleaseLeavesTheServerStoppedWithZeroRefCount() throws Exception {
    final String name = "concurrency-retain-release-service";
    final String location = "httpmocks/demo-service";

    runConcurrently(THREAD_COUNT, () -> EmbeddedFlowableHttpMockSupport.retain(name, location));
    assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isEqualTo(THREAD_COUNT);
    assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, location)).isTrue();

    releaseConcurrently(THREAD_COUNT, name, location);

    assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isZero();
    assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, location)).isFalse();
  }

  @Test
  void markConfiguredOnceReturnsTrueExactlyOnceUnderConcurrentAccess() throws Exception {
    final String name = "concurrency-configure-once-service";
    final String location = "httpmocks/demo-service";
    final WireMockServer server = EmbeddedFlowableHttpMockSupport.retain(name, location);

    try {
      final List<Boolean> results =
          runConcurrently(
              THREAD_COUNT, () -> EmbeddedFlowableHttpMockSupport.markConfiguredOnce(server));

      final long trueCount = results.stream().filter(Boolean::booleanValue).count();
      assertThat(trueCount).isEqualTo(1);
    } finally {
      releaseConcurrently(1, name, location);
    }
  }

  /**
   * Runs {@code action} once per thread, all threads released from a single {@link CountDownLatch}
   * gate simultaneously to maximize contention on {@link EmbeddedFlowableHttpMockSupport}'s
   * internal lock, rather than however the thread pool happened to schedule an un-gated burst of
   * submissions.
   */
  private static <T> List<T> runConcurrently(int threadCount, Callable<T> action) throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      final CountDownLatch startGate = new CountDownLatch(1);
      final List<Future<T>> futures =
          IntStream.range(0, threadCount)
              .mapToObj(
                  i ->
                      executor.submit(
                          () -> {
                            startGate.await();
                            return action.call();
                          }))
              .toList();
      startGate.countDown();

      final List<T> results = new ArrayList<>();
      for (final Future<T> future : futures) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdown();
    }
  }

  private static void releaseConcurrently(int threadCount, String name, String location)
      throws Exception {
    runConcurrently(
        threadCount,
        () -> {
          EmbeddedFlowableHttpMockSupport.release(name, location);
          return null;
        });
  }
}
