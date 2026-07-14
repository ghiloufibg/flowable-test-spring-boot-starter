package com.flowabletest.autoconfigure.kafka;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>{@link #acquireLease()}/{@link #releaseLease()} track how many still-open Spring contexts
 * currently hold an {@link EmbeddedKafkaSharedBrokerLease} on the shared broker, mirroring {@code
 * EmbeddedPostgresSupport}'s equivalent mechanism for the shared embedded Postgres server -- see
 * that class's Javadoc for why a naive "close as soon as the count reaches zero" policy is wrong
 * for a resource meant to be reused indefinitely across many, non-overlapping test contexts over
 * the JVM's whole lifetime, and why the count is instead consulted only once, by {@link
 * #closeAfterOutstandingLeasesDrain}, when {@link #startIfNeeded}'s own JVM shutdown hook actually
 * fires at JVM exit. Unlike the Postgres case, this hardens the shared broker's shutdown against
 * the same class of race rather than fixing an observed failure: Kafka client shutdown paths
 * (producer close, consumer container stop) already tolerate an unreachable broker gracefully
 * (logging a {@code WARN} and moving on), so the race was never seen to fail a destroy-method call
 * here the way it did for Flowable's JDBC-dependent engine beans.
 */
final class EmbeddedFlowableKafkaSupport {

  private static final Duration SHUTDOWN_WAIT_TIMEOUT = Duration.ofSeconds(30);

  private static final Object LOCK = new Object();
  private static volatile EmbeddedKafkaBroker broker;
  private static volatile EmbeddedKafkaBroker lastPerContextBroker;
  private static final AtomicInteger OUTSTANDING_LEASES = new AtomicInteger();

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
            .addShutdownHook(
                new Thread(
                    () -> closeAfterOutstandingLeasesDrain(started),
                    "flowable-test-embedded-kafka-shutdown"));
        broker = started;
      }
      return broker;
    }
  }

  /**
   * Waits, up to {@link #SHUTDOWN_WAIT_TIMEOUT}, for every still-cached Spring context holding an
   * {@link EmbeddedKafkaSharedBrokerLease} to release it before destroying the broker. JVM shutdown
   * hooks run concurrently in unspecified order by JDK design, so without this wait, this hook
   * could race Spring's own shutdown-hook-driven closing of those still-cached contexts. The
   * timeout is a safety net against a lease that never releases (a stuck context, a bug) hanging
   * JVM shutdown forever.
   */
  private static void closeAfterOutstandingLeasesDrain(final EmbeddedKafkaBroker started) {
    synchronized (LOCK) {
      final long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_TIMEOUT.toMillis();
      while (OUTSTANDING_LEASES.get() > 0) {
        final long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          break;
        }
        try {
          LOCK.wait(remaining);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    started.destroy();
  }

  static EmbeddedKafkaSharedBrokerLease acquireLease() {
    final EmbeddedKafkaBroker current = broker;
    if (current == null) {
      throw new IllegalStateException(
          "spring.kafka.bootstrap-servers was set but no embedded Kafka broker was started; "
              + "this indicates flowable-test-spring-boot-starter's own EnvironmentPostProcessor "
              + "did not run as expected.");
    }
    OUTSTANDING_LEASES.incrementAndGet();
    return new EmbeddedKafkaSharedBrokerLease(current);
  }

  static void releaseLease() {
    synchronized (LOCK) {
      OUTSTANDING_LEASES.decrementAndGet();
      LOCK.notifyAll();
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
