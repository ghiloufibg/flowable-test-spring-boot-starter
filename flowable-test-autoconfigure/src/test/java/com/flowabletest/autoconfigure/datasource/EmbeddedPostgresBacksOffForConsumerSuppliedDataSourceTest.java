package com.flowabletest.autoconfigure.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression test: with the default {@code instance-scope=per-context}, a consumer-supplied {@code
 * DataSource} bean must stop the raw {@link EmbeddedPostgres} bean from ever being created, not
 * just the {@code DataSource} bean built on top of it -- otherwise a native Postgres process still
 * forks on every context even though nothing in the context would ever use it.
 */
class EmbeddedPostgresBacksOffForConsumerSuppliedDataSourceTest {

  @Test
  void noEmbeddedPostgresProcessStartsWhenAConsumerSuppliesItsOwnDataSource() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlowableTestDatasourceAutoConfiguration.class))
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .withPropertyValues("flowable.test.datasource.provider=embedded-postgres")
        .run(context -> assertThat(context).doesNotHaveBean(EmbeddedPostgres.class));
  }
}
