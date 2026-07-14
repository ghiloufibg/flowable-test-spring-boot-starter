package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Starts (at most once per JVM) the shared embedded Postgres server used by {@code
 * instance-scope=shared} mode, mirroring {@code EmbeddedFlowableKafkaSupport}'s and {@code
 * EmbeddedFlowableHttpMockSupport}'s JVM-wide-singleton pattern. Deliberately split into two
 * responsibilities: {@link #sharedServer()} (lazy, at-most-once native process start) and {@link
 * #freshDatabase(EmbeddedPostgres)} (cheap, called once per Spring context, provisions an isolated
 * logical database on the already-running server via {@code CREATE DATABASE} rather than forking a
 * new process).
 *
 * <p>{@link #acquireLease()}/{@link #releaseLease()} track how many still-open Spring contexts
 * currently hold an {@link EmbeddedPostgresSharedServerLease} on the shared server. A naive "close
 * as soon as the count reaches zero" policy would be wrong here: this server is meant to be reused
 * indefinitely across many, non-overlapping test contexts over the JVM's whole lifetime (that is
 * the entire point of {@code shared} mode), and the count legitimately drops to zero between one
 * context's close and the next context's first use. The count is instead consulted only once, by
 * {@link #closeAfterOutstandingLeasesDrain}, when this class's own JVM shutdown hook actually fires
 * at JVM exit -- see that method's Javadoc for why waiting on it there is what prevents the
 * shutdown-hook race this class used to have.
 */
final class EmbeddedPostgresSupport {

  private static final Duration SHUTDOWN_WAIT_TIMEOUT = Duration.ofSeconds(30);

  private static final Object LOCK = new Object();
  private static volatile EmbeddedPostgres server;
  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
  private static final AtomicInteger START_COUNT = new AtomicInteger();
  private static final AtomicInteger OUTSTANDING_LEASES = new AtomicInteger();

  private EmbeddedPostgresSupport() {}

  static EmbeddedPostgres sharedServer() {
    final EmbeddedPostgres existing = server;
    if (existing != null) {
      return existing;
    }
    synchronized (LOCK) {
      if (server == null) {
        final EmbeddedPostgres started;
        try {
          // setRegisterShutdownHook(false): EmbeddedPostgres otherwise always registers its own
          // JVM shutdown hook (zonkyio/embedded-postgres#64, #87), independent of and racing the
          // explicit hook registered immediately below -- this class's own hook is the intended
          // sole owner of this JVM-wide singleton's lifecycle.
          started = EmbeddedPostgres.builder().setRegisterShutdownHook(false).start();
        } catch (final IOException e) {
          throw new IllegalStateException("Failed to start the shared embedded Postgres server", e);
        }
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> closeAfterOutstandingLeasesDrain(started),
                    "flowable-test-embedded-postgres-shutdown"));
        START_COUNT.incrementAndGet();
        server = started;
      }
      return server;
    }
  }

  /**
   * Waits, up to {@link #SHUTDOWN_WAIT_TIMEOUT}, for every still-cached Spring context holding an
   * {@link EmbeddedPostgresSharedServerLease} to release it before closing the server. JVM shutdown
   * hooks run concurrently in unspecified order by JDK design (specifically to avoid the deadlocks
   * that ordering guarantees between hooks would risk), so without this wait, this hook closing the
   * server could race Spring's own shutdown-hook-driven closing of those still-cached contexts: if
   * this hook wins, Flowable's engine beans in a context that hasn't finished closing yet fail
   * their own {@code destroy()} trying to open a JDBC connection to an already-closed server -- the
   * failure this class exists to prevent. {@link EmbeddedPostgresSharedServerLease#release()} is
   * wired, via a real bean dependency in {@code FlowableTestDatasourceAutoConfiguration} rather
   * than just fortunate timing, to run only after its own context's engine beans have already
   * finished their shutdown -- so once every outstanding lease has been released, every context
   * that was using this server is done with it, and closing is safe. The timeout is a safety net
   * against a lease that never releases (a stuck context, a bug) hanging JVM shutdown forever.
   */
  private static void closeAfterOutstandingLeasesDrain(final EmbeddedPostgres started) {
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
    try {
      started.close();
    } catch (final IOException e) {
      // JVM is already shutting down; nothing more to do.
    }
  }

  static EmbeddedPostgresSharedServerLease acquireLease() {
    final EmbeddedPostgres started = sharedServer();
    OUTSTANDING_LEASES.incrementAndGet();
    return new EmbeddedPostgresSharedServerLease(started);
  }

  static void releaseLease() {
    synchronized (LOCK) {
      OUTSTANDING_LEASES.decrementAndGet();
      LOCK.notifyAll();
    }
  }

  static DataSource freshDatabase(final EmbeddedPostgres server) {
    final String databaseName = "flowable_test_" + DATABASE_SEQUENCE.incrementAndGet();
    try (Connection connection = server.getPostgresDatabase().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE \"" + databaseName + "\"");
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Failed to provision database '%s' on the shared embedded Postgres server"
              .formatted(databaseName),
          e);
    }
    return server.getDatabase("postgres", databaseName);
  }

  /** Test-only accessor: how many times the shared server has actually been started this JVM. */
  static int startCount() {
    return START_COUNT.get();
  }
}
