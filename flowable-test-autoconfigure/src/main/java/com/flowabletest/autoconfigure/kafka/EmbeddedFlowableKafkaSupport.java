package com.flowabletest.autoconfigure.kafka;

import java.util.Set;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Starts the embedded Kafka broker(s) used by {@link FlowableTestKafkaEnvironmentPostProcessor}.
 * Deliberately not a Spring bean: it must be usable from an {@code EnvironmentPostProcessor}, which
 * runs before any {@code ApplicationContext} exists. {@link FlowableTestKafkaAutoConfiguration}
 * exposes the started instance as a regular bean afterwards, once the context is available, purely
 * so consumers can {@code @Autowired EmbeddedKafkaBroker} if they need direct access.
 *
 * <p>Two independent start paths, selected by {@link FlowableKafkaBrokerScopeCondition}: {@link
 * #startIfNeeded(Set, int)} starts at most one JVM-wide singleton broker ({@code shared}, the
 * default); {@link #startFresh(Set, int)} starts a brand-new broker every call ({@code
 * per-context}). {@link #currentPerContext()} hands the most recently {@link #startFresh started}
 * broker to the {@code @Bean} method that exposes it, relying on the same "at most one Spring
 * context builds at a time" sequential-execution assumption already documented for {@code shared}
 * mode's lifecycle listener -- the post-processor and the bean method that reads this field back
 * run synchronously within the same context's refresh.
 */
final class EmbeddedFlowableKafkaSupport {

  private static final Object LOCK = new Object();
  private static volatile EmbeddedKafkaBroker broker;
  private static volatile EmbeddedKafkaBroker lastPerContextBroker;

  private EmbeddedFlowableKafkaSupport() {}

  static EmbeddedKafkaBroker startIfNeeded(Set<String> topics, int partitions) {
    final EmbeddedKafkaBroker existing = broker;
    if (existing != null) {
      return existing;
    }
    synchronized (LOCK) {
      if (broker == null) {
        final EmbeddedKafkaBroker started = start(topics, partitions);
        Runtime.getRuntime()
            .addShutdownHook(new Thread(started::destroy, "flowable-test-embedded-kafka-shutdown"));
        broker = started;
      }
      return broker;
    }
  }

  static EmbeddedKafkaBroker current() {
    return broker;
  }

  static EmbeddedKafkaBroker startFresh(Set<String> topics, int partitions) {
    final EmbeddedKafkaBroker started = start(topics, partitions);
    lastPerContextBroker = started;
    return started;
  }

  static EmbeddedKafkaBroker currentPerContext() {
    return lastPerContextBroker;
  }

  private static EmbeddedKafkaBroker start(Set<String> topics, int partitions) {
    final EmbeddedKafkaBroker started =
        new EmbeddedKafkaKraftBroker(1, partitions, topics.toArray(new String[0]));
    try {
      started.afterPropertiesSet();
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to start the embedded Kafka broker", e);
    }
    return started;
  }
}
