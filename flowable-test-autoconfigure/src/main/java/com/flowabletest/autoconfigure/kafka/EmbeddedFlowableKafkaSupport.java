package com.flowabletest.autoconfigure.kafka;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>{@link #acquireLease()}/{@link #releaseLease()} count how many still-open Spring contexts hold
 * an {@link EmbeddedKafkaSharedBrokerLease} on the shared broker. That count is consulted only
 * once, by {@link #closeAfterOutstandingLeasesDrain}, when the shutdown hook registered in {@link
 * #startIfNeeded} fires at JVM exit -- the broker is meant to be reused indefinitely across many,
 * non-overlapping test contexts for the JVM's whole lifetime, so it must not be torn down simply
 * because the count momentarily reaches zero between contexts. Kafka client shutdown paths
 * (producer close, consumer container stop) already tolerate an unreachable broker gracefully, so
 * this hardens the shared broker's shutdown against a class of race rather than fixing an observed
 * failure.
 */
final class EmbeddedFlowableKafkaSupport {

  private static final Duration SHUTDOWN_WAIT_TIMEOUT = Duration.ofSeconds(30);

  private static final Object LOCK = new Object();
  private static volatile EmbeddedKafkaBroker broker;
  private static final ThreadLocal<EmbeddedKafkaBroker> PER_CONTEXT_BROKER = new ThreadLocal<>();
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
