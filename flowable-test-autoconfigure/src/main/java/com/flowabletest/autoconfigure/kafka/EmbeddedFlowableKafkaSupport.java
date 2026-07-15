package com.flowabletest.autoconfigure.kafka;

import java.util.Arrays;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Starts and owns the embedded Kafka broker(s) used by {@link
 * FlowableTestKafkaEnvironmentPostProcessor}. Deliberately not a Spring bean: it must be usable
 * from an {@code EnvironmentPostProcessor}, which runs before any {@code ApplicationContext}
 * exists. {@link FlowableTestKafkaAutoConfiguration} exposes the started instance as a regular bean
 * afterwards so consumers can {@code @Autowired EmbeddedKafkaBroker} directly.
 *
 * <p>Two independent start paths, selected by {@link FlowableKafkaBrokerScopeCondition}: {@link
 * #startIfNeeded(Set, int)} starts at most one JVM-wide singleton broker ({@code shared}, the
 * default); {@link #startFresh(Set, int)} starts a brand-new broker on every call ({@code
 * per-context}). {@link #currentPerContext()} hands the most recently {@link #startFresh started}
 * broker back to the {@code @Bean} method that exposes it. That hand-off is scoped per {@link
 * ThreadLocal}, not a plain static field: {@code EnvironmentPostProcessor} and that later
 * {@code @Bean} method always run on the same thread for a given context's own refresh, but under
 * concurrent test execution (e.g. two {@code SEPARATE_CONTEXT} classes with {@code
 * broker-scope=per-context} refreshing at the same time on different threads) a plain static field
 * would let one context's {@link #startFresh} call overwrite another's before that other context's
 * {@code @Bean} method got to read it back, silently wiring the wrong broker into the wrong
 * context.
 *
 * <p>{@link #start} itself is fully serialized on {@link #LOCK}, independent of and in addition to
 * {@link #startIfNeeded}'s own double-checked locking around it: constructing two {@code
 * EmbeddedKafkaKraftBroker} instances concurrently anywhere in this JVM -- shared or per-context,
 * any combination -- throws {@code IllegalArgumentException: A metric named '...' already exists},
 * because the underlying Kafka test-kit registers broker-metadata metrics in a registry that is not
 * scoped per broker instance. Serializing construction only blocks other broker startups for the
 * (short) duration of one broker's own startup, never a whole context's lifetime, so it does not
 * reintroduce the JVM-wide-singleton cost {@code per-context} mode exists to avoid.
 *
 * <p>The shared broker started by {@link #startIfNeeded} is reused indefinitely across every
 * non-overlapping test context for the JVM's whole lifetime and torn down only once, by the
 * shutdown hook registered there, at JVM exit -- deliberately not on a per-context basis. This
 * mirrors Spring Kafka's own recommended pattern for sharing one broker across test classes (a
 * plain start-once singleton, cleaned up only at JVM exit), rather than attempting deterministic
 * per-context teardown: Spring's {@code TestContext} cache does not close cached contexts via JVM
 * shutdown hooks on the normal (non-AOT) path this starter runs under, so there is nothing for a
 * per-context reference count to meaningfully protect against here.
 *
 * <p>{@link #addTopicsIfMissing} is the one other operation that mutates a broker already handed
 * out to a context ({@link EmbeddedFlowableKafkaContextCustomizer} calls it for {@code
 * additionalTopics()} on an already-running shared broker) and is synchronized on its own {@link
 * #TOPIC_LOCK}, separate from {@link #LOCK}, so a fast topic-add on one context's already-running
 * broker never blocks behind an unrelated context's own multi-second broker startup.
 */
final class EmbeddedFlowableKafkaSupport {

  private static final Object LOCK = new Object();
  private static final Object TOPIC_LOCK = new Object();
  private static volatile EmbeddedKafkaBroker broker;
  private static final ThreadLocal<EmbeddedKafkaBroker> PER_CONTEXT_BROKER = new ThreadLocal<>();

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

  /**
   * Adds every entry of {@code requestedTopics} not already present on {@code broker}, filtering
   * and adding under {@link #TOPIC_LOCK} so two threads racing this method against the same running
   * broker (e.g. two {@code SEPARATE_CONTEXT} classes with overlapping {@code additionalTopics()}
   * refreshing concurrently) cannot both observe a topic as missing and both call {@code
   * addTopics()} for it -- the underlying admin call throws {@code TopicExistsException} if a
   * second caller loses that race.
   */
  static void addTopicsIfMissing(
      EmbeddedKafkaBroker broker, String[] requestedTopics, int partitions) {
    synchronized (TOPIC_LOCK) {
      final Set<String> existingTopics = broker.getTopics();
      final NewTopic[] topicsToAdd =
          Arrays.stream(requestedTopics)
              .filter(topic -> !existingTopics.contains(topic))
              .map(topic -> new NewTopic(topic, partitions, (short) 1))
              .toArray(NewTopic[]::new);
      if (topicsToAdd.length > 0) {
        broker.addTopics(topicsToAdd);
      }
    }
  }

  static EmbeddedKafkaBroker startFresh(Set<String> topics, int partitions) {
    final EmbeddedKafkaBroker started = start(topics, partitions);
    PER_CONTEXT_BROKER.set(started);
    return started;
  }

  /**
   * Removes the {@link ThreadLocal} entry once read, not just returns it -- once this context's
   * {@code @Bean} method has consumed it, keeping the reference around would only pin this (pooled,
   * reused-across-many-test-classes) thread's last per-context broker in memory for no further
   * purpose.
   */
  static EmbeddedKafkaBroker currentPerContext() {
    final EmbeddedKafkaBroker started = PER_CONTEXT_BROKER.get();
    PER_CONTEXT_BROKER.remove();
    return started;
  }

  private static EmbeddedKafkaBroker start(Set<String> topics, int partitions) {
    synchronized (LOCK) {
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
}
