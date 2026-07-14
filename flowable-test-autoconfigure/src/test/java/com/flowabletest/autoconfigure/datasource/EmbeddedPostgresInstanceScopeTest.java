package com.flowabletest.autoconfigure.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Unit-level proof of {@code instance-scope=shared} mode: one native process is started at most
 * once per JVM, and each Spring context still gets its own isolated logical database on top of it.
 * Uses {@link ApplicationContextRunner} directly against the autoconfiguration class (rather than a
 * full {@code @FlowableProcessTest}) so a single test method can deterministically open two
 * independent contexts back to back and inspect {@link EmbeddedPostgresSupport}'s package-private
 * start-count accessor -- something a real, Spring-TestContext-cached {@code ApplicationContext}
 * doesn't let a test control directly.
 */
class EmbeddedPostgresInstanceScopeTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FlowableTestDatasourceAutoConfiguration.class))
          .withPropertyValues(
              "flowable.test.datasource.provider=embedded-postgres",
              "flowable.test.datasource.embedded-postgres.instance-scope=shared");

  @Test
  void sharedModeReusesOneServerAcrossContextsWithIsolatedDatabasesAndFasterSubsequentSetup()
      throws Exception {
    final int startCountBefore = EmbeddedPostgresSupport.startCount();

    final long firstContextNanos;
    final String[] firstDatabaseName = new String[1];
    final long firstStart = System.nanoTime();
    contextRunner.run(
        firstContext -> {
          firstDatabaseName[0] = currentDatabase(firstContext.getBean(DataSource.class));
        });
    firstContextNanos = System.nanoTime() - firstStart;

    final long secondContextNanos;
    final String[] secondDatabaseName = new String[1];
    final long secondStart = System.nanoTime();
    contextRunner.run(
        secondContext -> {
          secondDatabaseName[0] = currentDatabase(secondContext.getBean(DataSource.class));
        });
    secondContextNanos = System.nanoTime() - secondStart;

    assertThat(secondDatabaseName[0]).isNotEqualTo(firstDatabaseName[0]);
    assertThat(EmbeddedPostgresSupport.startCount()).isEqualTo(startCountBefore + 1);
    assertThat(secondContextNanos).isLessThan(firstContextNanos);
  }

  private static String currentDatabase(final DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT current_database()")) {
      resultSet.next();
      return resultSet.getString(1);
    }
  }
}
