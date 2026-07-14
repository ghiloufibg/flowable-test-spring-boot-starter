package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * A per-context handle on the JVM-wide shared embedded Postgres server, returned by {@link
 * EmbeddedPostgresSupport#acquireLease()}. Registered as a {@code @Bean(destroyMethod = "release")}
 * in {@link FlowableTestDatasourceAutoConfiguration}, with the shared {@code DataSource} bean
 * depending on it so that Spring destroys this lease only after its own context's Flowable engine
 * beans have finished shutting down -- see {@link EmbeddedPostgresSupport} for why that ordering is
 * what prevents the shared server's shutdown hook from closing the server while those engine beans
 * are still mid-shutdown.
 */
final class EmbeddedPostgresSharedServerLease {

  private final EmbeddedPostgres server;

  EmbeddedPostgresSharedServerLease(final EmbeddedPostgres server) {
    this.server = server;
  }

  EmbeddedPostgres server() {
    return server;
  }

  void release() {
    EmbeddedPostgresSupport.releaseLease();
  }
}
