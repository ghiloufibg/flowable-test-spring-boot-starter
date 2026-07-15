package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Regression test: this condition must match only when {@link
 * FlowableTestKafkaEnvironmentPostProcessor} actually provisioned a broker for this context, never
 * merely because {@code spring.kafka.bootstrap-servers} happens to be set -- a consumer can set
 * that property independently (a real broker, Testcontainers) with no Kafka Event Registry channel
 * descriptors on the classpath at all.
 */
class FlowableTestKafkaProvisionedConditionTest {

  private final FlowableTestKafkaProvisionedCondition condition =
      new FlowableTestKafkaProvisionedCondition();

  @Test
  void matchesWhenThePostProcessorsPropertySourceIsPresent() {
    final MockEnvironment environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                FlowableTestKafkaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME,
                Map.of("spring.kafka.bootstrap-servers", "localhost:9092")));

    assertThat(condition.matches(conditionContextFor(environment), null)).isTrue();
  }

  @Test
  void doesNotMatchWhenOnlyBootstrapServersIsSetBySomeoneElse() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.kafka.bootstrap-servers", "localhost:9092");

    assertThat(condition.matches(conditionContextFor(environment), null)).isFalse();
  }

  private static ConditionContext conditionContextFor(MockEnvironment environment) {
    final ConditionContext context = mock(ConditionContext.class);
    when(context.getEnvironment()).thenReturn(environment);
    return context;
  }
}
