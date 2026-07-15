package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowabletest.core.http.HttpMockServers;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Regression test: a consumer-supplied {@link HttpMockServers} bean must make {@code
 * httpMockServersReleaseListener} back off too, not just {@code httpMockServers} -- otherwise the
 * listener still decrements {@link EmbeddedFlowableHttpMockSupport}'s refcount for services this
 * context never retained, risking a premature shutdown of a JVM-shared server another context still
 * depends on. Both beans now live in the same
 * {@code @ConditionalOnMissingBean(HttpMockServers.class)} nested configuration, so they must
 * always activate or back off together.
 */
class HttpMockServersReleaseListenerBacksOffForConsumerSuppliedBeanTest {

  @Test
  void releaseListenerBeanIsAbsentWhenAConsumerSuppliesItsOwnHttpMockServers() {
    final HttpMockServers consumerSupplied = new HttpMockServers(Map.of());

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlowableTestHttpStubAutoConfiguration.class))
        .withBean(ProcessEngine.class, () -> mock(ProcessEngine.class))
        .withBean(HttpMockServers.class, () -> consumerSupplied)
        .run(
            context -> {
              assertThat(context.getBean(HttpMockServers.class)).isSameAs(consumerSupplied);
              assertThat(context).doesNotHaveBean("httpMockServersReleaseListener");
            });
  }
}
