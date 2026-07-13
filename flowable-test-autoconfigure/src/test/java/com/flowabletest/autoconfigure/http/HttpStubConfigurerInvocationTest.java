package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.http.HttpStubConfigurer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves an {@link HttpStubConfigurer} bean is invoked exactly once per distinct, shared {@code
 * WireMockServer} instance -- not once per Spring context that retains it. Before this fix, every
 * context sharing a JVM-wide default server would re-run every configurer against it, so a
 * configurer registering a dynamic stub would accumulate a duplicate registration per context.
 */
class HttpStubConfigurerInvocationTest {

  @Test
  void configurerRunsOnceAcrossMultipleContextsSharingTheSameServer() {
    final String name = "configurer-once-service";
    final String location = "httpmocks/demo-service";
    final AtomicInteger invocationCount = new AtomicInteger();
    final HttpStubConfigurer countingConfigurer =
        (serviceName, server) -> invocationCount.incrementAndGet();

    final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlowableTestHttpStubAutoConfiguration.class))
            .withBean(HttpStubConfigurer.class, () -> countingConfigurer)
            .withPropertyValues("flowable.test.http-mocks.discovered=" + name + "=" + location);

    runner.run(
        firstContext -> runner.run(secondContext -> assertThat(invocationCount).hasValue(1)));
  }
}
