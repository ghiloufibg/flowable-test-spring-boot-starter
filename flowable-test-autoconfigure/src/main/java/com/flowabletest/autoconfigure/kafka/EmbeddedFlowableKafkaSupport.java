package com.flowabletest.autoconfigure.kafka;

import java.util.Set;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Starts (at most once per JVM) the embedded Kafka broker used by {@link
 * FlowableTestKafkaEnvironmentPostProcessor}. Deliberately not a Spring bean: it must be usable
 * from an {@code EnvironmentPostProcessor}, which runs before any {@code ApplicationContext}
 * exists. {@link FlowableTestKafkaAutoConfiguration} exposes the same instance as a regular bean
 * afterwards, once the context is available, purely so consumers can {@code @Autowired
 * EmbeddedKafkaBroker} if they need direct access.
 */
final class EmbeddedFlowableKafkaSupport {

  private static final Object LOCK = new Object();
  private static volatile EmbeddedKafkaBroker broker;

  private EmbeddedFlowableKafkaSupport() {}

  static EmbeddedKafkaBroker startIfNeeded(Set<String> topics, int partitions) {
    EmbeddedKafkaBroker existing = broker;
    if (existing != null) {
      return existing;
    }
    synchronized (LOCK) {
      if (broker == null) {
        EmbeddedKafkaBroker started =
            new EmbeddedKafkaKraftBroker(1, partitions, topics.toArray(new String[0]));
        try {
          started.afterPropertiesSet();
        } catch (Exception e) {
          throw new IllegalStateException("Failed to start the embedded Kafka broker", e);
        }
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
}
