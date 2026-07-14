package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * A per-context handle on the JVM-wide shared embedded Postgres server, returned by {@link
 * EmbeddedPostgresSupport#acquireLease()}. {@link #release()} is registered as this bean's Spring
 * {@code destroyMethod} in {@link FlowableTestDatasourceAutoConfiguration}; see {@link
 * EmbeddedPostgresSupport}'s Javadoc for why this bean's destroy-order placement -- after its own
 * context's Flowable engine beans, via a real dependency edge on the {@code DataSource} bean rather
 * than just fortunate timing -- is what actually prevents the shared server's shutdown hook from
 * closing it while those engine beans are still mid-shutdown.
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
