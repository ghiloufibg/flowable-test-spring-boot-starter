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
 * Proves the {@code KafkaTestBridge} bean's broader activation: it must still register when {@code
 * flowable.test.kafka.enabled=false} and the consumer points {@code spring.kafka.bootstrap-servers}
 * at a real broker themselves, as long as this project actually declares Kafka Event Registry
 * channels -- but never merely because some unrelated {@code spring.kafka.bootstrap-servers}
 * happens to be set with no channels on the classpath at all.
 */
class FlowableTestKafkaBridgeConditionTest {

  private final FlowableTestKafkaBridgeCondition condition = new FlowableTestKafkaBridgeCondition();

  @Test
  void matchesWhenTheStarterProvisionedItsOwnEmbeddedBroker() {
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
  void matchesWhenRealChannelsExistAndBootstrapServersPointsAtAConsumerSuppliedBroker() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.kafka.bootstrap-servers", "localhost:9093");
    environment.setProperty(
        "flowable.test.kafka.channel-location", "classpath*:channel-fixtures/*.channel");

    assertThat(condition.matches(conditionContextFor(environment), null)).isTrue();
  }

  @Test
  void doesNotMatchWhenBootstrapServersIsUnset() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "flowable.test.kafka.channel-location", "classpath*:channel-fixtures/*.channel");

    assertThat(condition.matches(conditionContextFor(environment), null)).isFalse();
  }

  @Test
  void doesNotMatchWhenBootstrapServersIsSetButNoKafkaChannelsAreDeclared() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.kafka.bootstrap-servers", "localhost:9093");
    environment.setProperty(
        "flowable.test.kafka.channel-location",
        "classpath*:channel-fixtures/non-kafka-channel.channel");

    assertThat(condition.matches(conditionContextFor(environment), null)).isFalse();
  }

  private static ConditionContext conditionContextFor(MockEnvironment environment) {
    final ConditionContext context = mock(ConditionContext.class);
    when(context.getEnvironment()).thenReturn(environment);
    return context;
  }
}
