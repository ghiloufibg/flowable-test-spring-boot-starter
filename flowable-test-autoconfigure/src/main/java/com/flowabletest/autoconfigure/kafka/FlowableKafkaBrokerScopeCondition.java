package com.flowabletest.autoconfigure.kafka;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Reads {@code flowable.test.kafka.broker-scope} to decide whether the embedded Kafka broker
 * exposed by {@link FlowableTestKafkaAutoConfiguration} is a JVM-wide singleton shared across every
 * Spring context ({@code shared}, the default) or a fresh broker started per Spring context ({@code
 * per-context}). {@link #isShared(Environment)} throws {@link IllegalStateException} for any other
 * value, and is also called directly -- not only through {@link Condition} -- from {@link
 * FlowableTestKafkaEnvironmentPostProcessor} and {@link
 * FlowableKafkaConsumerLifecycleTestExecutionListener}, both of which only have an {@link
 * Environment} to read, not a {@link ConditionContext}.
 *
 * <p>Exposed as two nested {@link Condition} implementations, {@link Shared} and {@link
 * PerContext}, so the corresponding {@code @Bean} methods remain mutually exclusive
 * {@code @Conditional} gates rather than a single method branching internally.
 */
final class FlowableKafkaBrokerScopeCondition {

  static final String BROKER_SCOPE_PROPERTY = "flowable.test.kafka.broker-scope";

  private FlowableKafkaBrokerScopeCondition() {}

  static boolean isShared(final Environment environment) {
    final String scope = environment.getProperty(BROKER_SCOPE_PROPERTY, "shared");
    return switch (scope) {
      case "shared" -> true;
      case "per-context" -> false;
      default ->
          throw new IllegalStateException(
              "%s=%s is not supported -- expected 'shared' or 'per-context'."
                  .formatted(BROKER_SCOPE_PROPERTY, scope));
    };
  }

  static final class Shared implements Condition {
    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
      return isShared(context.getEnvironment());
    }
  }

  static final class PerContext implements Condition {
    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
      return !isShared(context.getEnvironment());
    }
  }
}
