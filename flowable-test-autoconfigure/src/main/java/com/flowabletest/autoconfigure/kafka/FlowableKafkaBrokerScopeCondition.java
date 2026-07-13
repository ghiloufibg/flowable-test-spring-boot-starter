package com.flowabletest.autoconfigure.kafka;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether the embedded Kafka broker exposed by {@link FlowableTestKafkaAutoConfiguration}
 * is a JVM-wide singleton shared across every Spring context ({@code shared}, the default) or a
 * fresh broker started per Spring context ({@code per-context}) -- see {@code
 * claudedocs/kafka-shared-broker-context-isolation-design.md}. Same shape as {@code
 * EmbeddedPostgresInstanceScopeCondition}: a small helper plus two trivial {@link Condition}
 * implementations, so the two bean methods stay mutually exclusive {@code @Conditional} gates
 * rather than one method branching internally. {@link #isShared(Environment)} is also called
 * directly (not via {@link Condition}) from {@link FlowableTestKafkaEnvironmentPostProcessor} and
 * {@link FlowableKafkaConsumerLifecycleTestExecutionListener}, both of which only have an {@link
 * Environment} to read, not a {@link ConditionContext}.
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
