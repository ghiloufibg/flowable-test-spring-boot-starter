package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Unit-level proof of fix #2 from {@code claudedocs/wiremock-shared-server-fixes-design.md}: a
 * WireMock server's lifetime is bounded by the Spring contexts that {@code retain} it via {@link
 * FlowableTestHttpStubAutoConfiguration#httpMockServers}, not the whole JVM. Uses {@link
 * ApplicationContextRunner} directly against the autoconfiguration class (rather than a full
 * {@code @FlowableProcessTest}) so each test can drive context open/close precisely and inspect
 * {@link EmbeddedFlowableHttpMockSupport}'s package-private refcount/liveness state -- something a
 * real, Spring-TestContext-cached {@code ApplicationContext} doesn't let a test control directly.
 *
 * <p>Each test uses a service name unique to itself (though pointed at the already-existing {@code
 * httpmocks/demo-service} / {@code httpmocks-alt/demo-service} mapping folders other tests also
 * use) so its refcounts can't be polluted by other test classes' real, JVM-lifetime-cached contexts
 * retaining the literal {@code "demo-service"} key.
 */
class EmbeddedFlowableHttpMockSupportLifecycleTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FlowableTestHttpStubAutoConfiguration.class));

  @Test
  void sharedDefaultServerSurvivesOneContextCloseAndStopsAfterTheLast() {
    final String name = "lifecycle-shared-service";
    final String location = "httpmocks/demo-service";
    final ApplicationContextRunner runner =
        contextRunner.withPropertyValues(
            "flowable.test.http-mocks.discovered=" + name + "=" + location);

    runner.run(
        firstContext -> {
          assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isEqualTo(1);
          assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, location)).isTrue();

          runner.run(
              secondContext ->
                  assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location))
                      .isEqualTo(2));

          assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isEqualTo(1);
          assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, location)).isTrue();
        });

    assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, location)).isEqualTo(0);
    assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, location)).isFalse();
  }

  @Test
  void overrideOnlyServerIsStoppedWhenItsSingleContextCloses() {
    final String name = "lifecycle-override-service";
    final String defaultLocation = "httpmocks/demo-service";
    final String overrideLocation = "httpmocks-alt/demo-service";

    contextRunner
        .withPropertyValues(
            "flowable.test.http-mocks.discovered=" + name + "=" + defaultLocation,
            "flowable.test.http-mocks.overridden=" + name + "=" + overrideLocation)
        .run(
            context -> {
              assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, overrideLocation))
                  .isEqualTo(1);
              assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, overrideLocation))
                  .isTrue();

              // The shadowed default was never resolved into this context's final map, so
              // httpMockServers never retained it -- it must not be running because of this
              // context.
              assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, defaultLocation)).isZero();
              assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, defaultLocation))
                  .isFalse();
            });

    assertThat(EmbeddedFlowableHttpMockSupport.refCount(name, overrideLocation)).isZero();
    assertThat(EmbeddedFlowableHttpMockSupport.isRunning(name, overrideLocation)).isFalse();
  }
}
