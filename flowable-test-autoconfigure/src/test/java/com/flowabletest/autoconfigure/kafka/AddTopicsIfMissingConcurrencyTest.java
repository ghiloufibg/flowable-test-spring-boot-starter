package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Regression test: {@link EmbeddedFlowableKafkaSupport#addTopicsIfMissing} must be safe to call
 * from several threads at once against the same already-running broker for an overlapping topic set
 * -- the scenario two {@code SEPARATE_CONTEXT} test classes with overlapping
 * {@code @EmbeddedFlowableKafka(additionalTopics = ...)} refreshing concurrently produce. Before
 * this fix, {@link EmbeddedFlowableKafkaContextCustomizer} performed this same
 * read-check-then-write sequence directly against the broker with no synchronization, so two
 * threads could both observe a topic as missing and both call {@code addTopics()} for it, one
 * losing to {@code TopicExistsException}.
 */
class AddTopicsIfMissingConcurrencyTest {

  private static final int CONCURRENT_CALLS = 10;
  private static final String CONTENDED_TOPIC = "contended-topic";

  private static final CyclicBarrier START_TOGETHER = new CyclicBarrier(CONCURRENT_CALLS);

  @Test
  void concurrentCallsForTheSameMissingTopicNeverThrowTopicExistsException() throws Exception {
    final EmbeddedKafkaBroker broker = EmbeddedFlowableKafkaSupport.startFresh(Set.of(), 1);
    try {
      final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CALLS);
      try {
        final List<Callable<Void>> tasks =
            IntStream.range(0, CONCURRENT_CALLS)
                .<Callable<Void>>mapToObj(i -> () -> addContendedTopicAfterRendezvous(broker))
                .toList();
        final List<Future<Void>> futures = executor.invokeAll(tasks);
        for (final Future<Void> future : futures) {
          future.get();
        }
      } finally {
        executor.shutdown();
      }

      assertThat(broker.getTopics()).contains(CONTENDED_TOPIC);
    } finally {
      broker.destroy();
    }
  }

  private static Void addContendedTopicAfterRendezvous(EmbeddedKafkaBroker broker) {
    awaitBarrier();
    EmbeddedFlowableKafkaSupport.addTopicsIfMissing(broker, new String[] {CONTENDED_TOPIC}, 1);
    return null;
  }

  private static void awaitBarrier() {
    try {
      START_TOGETHER.await();
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to rendezvous before concurrent topic add", e);
    }
  }
}
