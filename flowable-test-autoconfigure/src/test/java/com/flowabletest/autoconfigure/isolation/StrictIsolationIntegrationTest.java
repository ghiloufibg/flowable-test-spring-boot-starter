package com.flowabletest.autoconfigure.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.FlowableTestIsolation;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContextManager;

/**
 * Proves {@code isolation = SEPARATE_CONTEXT} actually forces two distinct {@code
 * ApplicationContext}s -- and therefore two distinct {@code ProcessEngine}s and databases -- for
 * two test classes whose merged configuration is otherwise identical (both
 * {@code @FlowableProcessTest(classes = SampleFlowableApplication.class)}, no other class-specific
 * customization), which Spring's ordinary {@code TestContextCache} would otherwise judge
 * cacheable-together, as the {@code SHARED} baseline test below confirms actually happens for
 * {@code isolation}'s default value.
 *
 * <p>Drives {@link TestContextManager} directly, mirroring {@code
 * FlowableKafkaConsumerLifecycleTestExecutionListenerTest}, rather than relying on real multi-class
 * JUnit execution order.
 */
class StrictIsolationIntegrationTest {

  @Test
  void twoIdenticallyConfiguredSharedClassesReuseOneCachedContext() throws Exception {
    final ApplicationContext first = contextFor(SharedTestClassA.class);
    final ApplicationContext second = contextFor(SharedTestClassB.class);

    assertThat(first).isSameAs(second);
  }

  @Test
  void twoIdenticallyConfiguredSeparateContextClassesNeverShareAContext() throws Exception {
    final ApplicationContext first = contextFor(SeparateContextTestClassA.class);
    final ApplicationContext second = contextFor(SeparateContextTestClassB.class);

    assertThat(first).isNotSameAs(second);
  }

  /**
   * Reproduces the exact footgun {@link StrictIsolationContextCustomizer} exists to close: two
   * {@code SEPARATE_CONTEXT} classes that both explicitly pin the identical, fixed {@code
   * spring.datasource.url} a consumer's shared {@code application.yml} commonly hardcodes (e.g.
   * {@code jdbc:h2:mem:testdb}). Without the customizer's override, both contexts would connect to
   * the exact same physical H2 database despite being genuinely separate {@code
   * ApplicationContext}s.
   */
  @Test
  void separateContextClassesGetIsolatedDatabasesEvenWithAnIdenticalPinnedH2Url() throws Exception {
    final ApplicationContext first = contextFor(FixedUrlSeparateContextTestClassA.class);
    final ApplicationContext second = contextFor(FixedUrlSeparateContextTestClassB.class);

    assertThat(jdbcUrlOf(first)).isNotEqualTo(jdbcUrlOf(second));
  }

  private static String jdbcUrlOf(ApplicationContext context) throws Exception {
    final DataSource dataSource = context.getBean(DataSource.class);
    try (Connection connection = dataSource.getConnection()) {
      return connection.getMetaData().getURL();
    }
  }

  private static ApplicationContext contextFor(Class<?> testClass) throws Exception {
    final TestContextManager testContextManager = new TestContextManager(testClass);
    testContextManager.beforeTestClass();
    return testContextManager.getTestContext().getApplicationContext();
  }

  @FlowableProcessTest(classes = SampleFlowableApplication.class)
  static class SharedTestClassA {}

  @FlowableProcessTest(classes = SampleFlowableApplication.class)
  static class SharedTestClassB {}

  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      isolation = FlowableTestIsolation.SEPARATE_CONTEXT)
  static class SeparateContextTestClassA {}

  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      isolation = FlowableTestIsolation.SEPARATE_CONTEXT)
  static class SeparateContextTestClassB {}

  private static final String PINNED_H2_URL =
      "spring.datasource.url=jdbc:h2:mem:strict-isolation-fixed-name;DB_CLOSE_DELAY=-1";

  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      isolation = FlowableTestIsolation.SEPARATE_CONTEXT,
      properties = PINNED_H2_URL)
  static class FixedUrlSeparateContextTestClassA {}

  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      isolation = FlowableTestIsolation.SEPARATE_CONTEXT,
      properties = PINNED_H2_URL)
  static class FixedUrlSeparateContextTestClassB {}
}
