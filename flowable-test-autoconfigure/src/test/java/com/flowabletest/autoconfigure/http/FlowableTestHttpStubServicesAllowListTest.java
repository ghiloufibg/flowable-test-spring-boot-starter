package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Proves {@code flowable.test.http-mocks.services}, driving {@link SpringApplicationBuilder}
 * directly rather than {@code @FlowableProcessTest} or {@code ApplicationContextRunner} -- same
 * rationale as {@code FlowableKafkaBrokerScopePerContextTest}: {@code ApplicationContextRunner}
 * bypasses the {@code EnvironmentPostProcessor} SPI entirely, which is where this property is
 * actually read.
 *
 * <p>Uses a dedicated {@code httpmocks-services-test} root (two subfolders, {@code
 * declared-service} and {@code undeclared-service}) instead of the shared default {@code
 * httpmocks/} root every other test in this module scans, so declaring an allow-list here can't
 * change what any other, unrelated test observes.
 *
 * <p>Also forces {@code flowable.test.kafka.broker-scope=per-context}: unrelated to what this class
 * tests, but the JVM-wide singleton broker's {@code shared} default (see {@code
 * FlowableKafkaBrokerScopePerContextTest}) assumes the JUnit {@code TestExecutionListener}
 * lifecycle drives its start/stop choreography -- multiple raw {@link SpringApplicationBuilder}
 * contexts started back to back outside that lifecycle, as this class's tests do, don't satisfy
 * that assumption.
 */
class FlowableTestHttpStubServicesAllowListTest {

  private static final String ROOT = "classpath:httpmocks-services-test";

  @Test
  void servicesAbsent_fallsBackToScanningEveryFolder() {
    try (ConfigurableApplicationContext context = startContext(ROOT)) {
      final Environment environment = context.getEnvironment();

      assertThat(environment.getProperty("declared-service.base-url")).isNotBlank();
      assertThat(environment.getProperty("undeclared-service.base-url")).isNotBlank();
    }
  }

  @Test
  void servicesDeclared_onlyStartsDeclaredNames() {
    try (ConfigurableApplicationContext context =
        startContext(ROOT, "flowable.test.http-mocks.services=declared-service")) {
      final Environment environment = context.getEnvironment();

      assertThat(environment.getProperty("declared-service.base-url")).isNotBlank();
      assertThat(environment.getProperty("undeclared-service.base-url")).isNull();
    }
  }

  @Test
  void servicesDeclared_missingMappingsFolderFailsFastBeforeContextRefresh() {
    assertThatThrownBy(
            () ->
                startContext(
                    ROOT, "flowable.test.http-mocks.services=declared-service,missing-service"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing-service")
        .hasMessageContaining("httpmocks-services-test/missing-service/mappings");
  }

  private static ConfigurableApplicationContext startContext(String root, String... properties) {
    final SpringApplicationBuilder builder =
        new SpringApplicationBuilder(SampleFlowableApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "flowable.test.http-mocks.root=" + root,
                "flowable.test.kafka.broker-scope=per-context");
    builder.properties(properties);
    return builder.run();
  }
}
