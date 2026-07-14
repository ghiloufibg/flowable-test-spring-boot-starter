package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Embedded DB, Docker-free.
 *
 * <p>H2 is the fallback -- Spring Boot's own {@code DataSourceAutoConfiguration} wires an embedded
 * H2 {@code DataSource} automatically whenever H2 is on the test classpath and no explicit {@code
 * spring.datasource.url} is configured. This class additionally auto-detects {@code
 * io.zonky.test:embedded-postgres} on the classpath and, when present, replaces H2 with a
 * Docker-free embedded Postgres instance -- a direct, Docker-free replacement for a Testcontainers
 * {@code PostgreSQLContainer}, for projects whose delegates rely on Postgres-specific SQL (JSON
 * columns, arrays) that H2's dialect emulation can't cover.
 *
 * <p>{@code flowable.test.datasource.provider} controls the choice: {@code auto} (the default)
 * prefers embedded Postgres whenever it's on the classpath, {@code h2} always forces H2 even if
 * embedded Postgres is present, and {@code embedded-postgres} always requires it. See {@link
 * EmbeddedPostgresPreferredCondition}.
 *
 * <p>This class runs {@link AutoConfigureBefore before} Spring Boot's own {@code
 * DataSourceAutoConfiguration} so that its {@code @ConditionalOnMissingBean(DataSource.class)}
 * correctly backs off once the embedded-Postgres {@code DataSource} bean below has already been
 * registered.
 *
 * <p>Note: this does not override {@code spring.jpa.properties.hibernate.dialect}. If the
 * consumer's test profile hardcodes an H2 dialect, switching providers means removing that override
 * too (Hibernate 6 auto-detects the dialect from the JDBC connection when none is explicitly
 * configured).
 *
 * <p>{@code flowable.test.datasource.embedded-postgres.instance-scope} additionally controls *how*
 * the embedded-postgres provider allocates its server process: {@code per-context} (the default) forks
 * a fresh native process for every Spring context, exactly as below; {@code shared} starts at most
 * one process per JVM via {@link EmbeddedPostgresSupport} and provisions a fresh logical database
 * per context on top of it. See {@link EmbeddedPostgresInstanceScopeCondition}.
 */
@AutoConfiguration
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
public class FlowableTestDatasourceAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnClass(EmbeddedPostgres.class)
  @Conditional({
    EmbeddedPostgresPreferredCondition.class,
    EmbeddedPostgresInstanceScopeCondition.PerContext.class
  })
  EmbeddedPostgres embeddedPostgres() throws IOException {
    return EmbeddedPostgres.builder().start();
  }

  @Bean
  @ConditionalOnClass(EmbeddedPostgres.class)
  @Conditional({
    EmbeddedPostgresPreferredCondition.class,
    EmbeddedPostgresInstanceScopeCondition.PerContext.class
  })
  @ConditionalOnMissingBean(DataSource.class)
  DataSource embeddedPostgresDataSource(EmbeddedPostgres embeddedPostgres) {
    return embeddedPostgres.getPostgresDatabase();
  }

  @Bean(destroyMethod = "")
  @ConditionalOnClass(EmbeddedPostgres.class)
  @Conditional({
    EmbeddedPostgresPreferredCondition.class,
    EmbeddedPostgresInstanceScopeCondition.Shared.class
  })
  @ConditionalOnMissingBean(DataSource.class)
  DataSource embeddedPostgresSharedDataSource() {
    final EmbeddedPostgres server = EmbeddedPostgresSupport.sharedServer();
    return EmbeddedPostgresSupport.freshDatabase(server);
  }
}
