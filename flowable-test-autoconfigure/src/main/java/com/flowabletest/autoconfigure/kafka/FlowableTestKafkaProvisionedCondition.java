package com.flowabletest.autoconfigure.kafka;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches only when {@link FlowableTestKafkaEnvironmentPostProcessor} actually provisioned a broker
 * for this context, detected by the presence of its own {@link
 * FlowableTestKafkaEnvironmentPostProcessor#PROPERTY_SOURCE_NAME} property source -- not by
 * checking whether {@code spring.kafka.bootstrap-servers} happens to be set, since that property is
 * owned by Spring Kafka itself and a consumer may legitimately set it independently (a real broker,
 * Testcontainers) without this starter ever having started anything.
 */
final class FlowableTestKafkaProvisionedCondition implements Condition {

  @Override
  public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    return context.getEnvironment() instanceof ConfigurableEnvironment environment
        && environment
            .getPropertySources()
            .contains(FlowableTestKafkaEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
  }
}
