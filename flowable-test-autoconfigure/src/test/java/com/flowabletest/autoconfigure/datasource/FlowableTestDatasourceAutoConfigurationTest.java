package com.flowabletest.autoconfigure.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the embedded-DB capability end to end (design doc section 4.1). {@code
 * io.zonky.test:embedded-postgres} is on this module's own test classpath (it's an optional main
 * dependency of {@code flowable-test-autoconfigure} itself), so {@code
 * src/test/resources/application.yml} pins the rest of this module's tests to H2 explicitly and
 * each nested class here overrides {@code flowable.test.datasource.provider} to exercise one mode.
 */
class FlowableTestDatasourceAutoConfigurationTest {

  @Nested
  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      properties = "flowable.test.datasource.provider=auto")
  class AutoModeWithEmbeddedPostgresOnTheClasspath {

    @Autowired DataSource dataSource;

    @Test
    void prefersEmbeddedPostgresOverH2() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      }
    }
  }

  @Nested
  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      properties = "flowable.test.datasource.provider=embedded-postgres")
  class ExplicitEmbeddedPostgresProvider {

    @Autowired DataSource dataSource;

    @Test
    void usesEmbeddedPostgres() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      }
    }
  }

  @Nested
  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      properties = "flowable.test.datasource.provider=h2")
  class ExplicitH2ProviderOverridesAutoDetection {

    @Autowired DataSource dataSource;

    @Test
    void staysOnH2EvenThoughEmbeddedPostgresIsOnTheClasspath() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
      }
    }
  }
}
