package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Starts (at most once per JVM) the shared embedded Postgres server used by {@code
 * instance-scope=shared} mode, mirroring {@code
 * EmbeddedFlowableKafkaSupport}'s and {@code EmbeddedFlowableHttpMockSupport}'s JVM-wide-singleton
 * pattern. Deliberately split into two responsibilities: {@link #sharedServer()} (lazy,
 * at-most-once native process start) and {@link #freshDatabase(EmbeddedPostgres)} (cheap, called
 * once per Spring context, provisions an isolated logical database on the already-running server
 * via {@code CREATE DATABASE} rather than forking a new process).
 */
final class EmbeddedPostgresSupport {

  private static final Object LOCK = new Object();
  private static volatile EmbeddedPostgres server;
  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();
  private static final AtomicInteger START_COUNT = new AtomicInteger();

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
          started = EmbeddedPostgres.builder().start();
        } catch (final IOException e) {
          throw new IllegalStateException("Failed to start the shared embedded Postgres server", e);
        }
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      try {
                        started.close();
                      } catch (final IOException e) {
                        // JVM is already shutting down; nothing more to do.
                      }
                    },
                    "flowable-test-embedded-postgres-shutdown"));
        START_COUNT.incrementAndGet();
        server = started;
      }
      return server;
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
