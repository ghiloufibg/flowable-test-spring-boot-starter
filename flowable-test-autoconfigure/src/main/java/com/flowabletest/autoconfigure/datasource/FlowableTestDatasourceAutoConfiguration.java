package com.flowabletest.autoconfigure.datasource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Embedded DB, Docker-free (design doc section 4.1).
 *
 * <p>H2 is the default and needs no help from this class -- Spring Boot's own
 * {@code DataSourceAutoConfiguration} already wires an embedded H2 {@code DataSource}
 * automatically whenever H2 is on the test classpath and no explicit
 * {@code spring.datasource.url} is configured. This class only adds the opt-in
 * {@code embedded-postgres} provider, for projects whose delegates rely on Postgres-specific
 * SQL (JSON columns, arrays) that H2's dialect emulation can't cover -- a Docker-free
 * replacement for a Testcontainers {@code PostgreSQLContainer}, activated via
 * {@code flowable.test.datasource.provider=embedded-postgres}.
 *
 * <p>Note: this does not override {@code spring.jpa.properties.hibernate.dialect}. If the
 * consumer's test profile hardcodes an H2 dialect, switching providers means removing that
 * override too (Hibernate 6 auto-detects the dialect from the JDBC connection when none is
 * explicitly configured).
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
public class FlowableTestDatasourceAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnClass(EmbeddedPostgres.class)
    @ConditionalOnProperty(prefix = "flowable.test.datasource", name = "provider", havingValue = "embedded-postgres")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().start();
    }

    @Bean
    @ConditionalOnClass(EmbeddedPostgres.class)
    @ConditionalOnProperty(prefix = "flowable.test.datasource", name = "provider", havingValue = "embedded-postgres")
    @ConditionalOnMissingBean(DataSource.class)
    DataSource embeddedPostgresDataSource(EmbeddedPostgres embeddedPostgres) {
        return embeddedPostgres.getPostgresDatabase();
    }
}
