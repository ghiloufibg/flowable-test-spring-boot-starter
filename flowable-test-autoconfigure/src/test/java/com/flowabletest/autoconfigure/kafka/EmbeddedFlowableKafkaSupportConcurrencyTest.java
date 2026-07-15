package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Proves {@link EmbeddedFlowableKafkaSupport#startFresh} can be called from several threads at once
 * without colliding, and that the per-context hand-off ({@link
 * EmbeddedFlowableKafkaSupport#currentPerContext()}) is correctly scoped per calling thread: each
 * thread's own {@code currentPerContext()} call, immediately after its own {@code startFresh()}
 * call, must return that same thread's own broker, never a different thread's -- a plain static
 * field (rather than the {@link ThreadLocal} it is now) would let one thread's {@code startFresh}
 * overwrite another's before that other thread read it back.
 *
 * <p>Motivated by a real failure: a consumer project running several {@code SEPARATE_CONTEXT} test
 * classes with {@code broker-scope=per-context} concurrently hit {@code IllegalArgumentException: A
 * metric named '...' already exists} from {@code EmbeddedKafkaKraftBroker#afterPropertiesSet} --
 * the underlying Kafka test-kit registers broker-metadata metrics in a registry that isn't scoped
 * per broker instance, and {@link #start} used to have no synchronization around broker
 * construction at all. That collision is a narrow-window race under real, much longer-running
 * Spring contexts; a {@link CyclicBarrier} forcing several threads into {@code startFresh} at the
 * same instant here did not reliably reproduce it in isolation even without the fix, so this test
 * cannot serve as a deterministic regression guard for that specific exception -- {@link #start}'s
 * synchronization is still correct defense-in-depth (constructing two brokers at literally the same
 * instant is always unsafe, reproducible or not), and this test guards the hand-off correctness
 * that <em>is</em> reliably verifiable.
 *
 * <p>Exercises {@link EmbeddedFlowableKafkaSupport} directly rather than through a full {@code
 * SpringApplicationBuilder}-driven context (the pattern {@link
 * FlowableKafkaBrokerScopePerContextTest} uses for the sequential case): booting several genuinely
 * concurrent {@code SampleFlowableApplication} contexts also races Flowable's own Kafka Event
 * Registry listener-endpoint registration, a separate concern this class does not own. {@code
 * EmbeddedFlowableKafkaSupport} is deliberately not a Spring bean specifically so it can be called
 * this way, without any {@code ApplicationContext} involved.
 */
class EmbeddedFlowableKafkaSupportConcurrencyTest {

  private static final int CONCURRENT_STARTS = 10;

  /**
   * All {@link #CONCURRENT_STARTS} threads rendezvous here immediately before calling {@link
   * EmbeddedFlowableKafkaSupport#startFresh}, so every thread enters broker construction at
   * essentially the same instant -- the collision this test targets is a narrow-window race inside
   * {@code EmbeddedKafkaKraftBroker#afterPropertiesSet}, not something threads starting at
   * naturally-staggered times reliably hit.
   */
  private static final CyclicBarrier START_TOGETHER = new CyclicBarrier(CONCURRENT_STARTS);

  @Test
  void concurrentStartFreshCallsEachGetTheirOwnDistinctBrokerViaTheirOwnThread() throws Exception {
    final List<EmbeddedKafkaBroker> brokers = startFreshConcurrentlyFromSeveralThreads();
    try {
      assertThat(brokers).hasSize(CONCURRENT_STARTS);
      assertThat(brokers)
          .as("every concurrently-started broker must be a distinct instance")
          .doesNotHaveDuplicates();
    } finally {
      brokers.forEach(EmbeddedKafkaBroker::destroy);
    }
  }

  private static List<EmbeddedKafkaBroker> startFreshConcurrentlyFromSeveralThreads()
      throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_STARTS);
    try {
      final List<Callable<EmbeddedKafkaBroker>> tasks =
          IntStream.range(0, CONCURRENT_STARTS)
              .<Callable<EmbeddedKafkaBroker>>mapToObj(
                  i ->
                      EmbeddedFlowableKafkaSupportConcurrencyTest
                          ::startFreshAndReadBackOnThisThread)
              .toList();
      final List<Future<EmbeddedKafkaBroker>> futures = executor.invokeAll(tasks);
      final List<EmbeddedKafkaBroker> brokers = new ArrayList<>();
      for (final Future<EmbeddedKafkaBroker> future : futures) {
        brokers.add(future.get());
      }
      return brokers;
    } finally {
      executor.shutdown();
    }
  }

  private static EmbeddedKafkaBroker startFreshAndReadBackOnThisThread() {
    awaitBarrier();
    final EmbeddedKafkaBroker started = EmbeddedFlowableKafkaSupport.startFresh(Set.of(), 1);
    final EmbeddedKafkaBroker readBack = EmbeddedFlowableKafkaSupport.currentPerContext();
    assertThat(readBack)
        .as("currentPerContext() must return this same thread's own just-started broker")
        .isSameAs(started);
    return started;
  }

  private static void awaitBarrier() {
    try {
      START_TOGETHER.await();
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to rendezvous before concurrent broker start", e);
    }
  }
}
