package com.flowabletest.autoconfigure.datasource;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Reads {@code flowable.test.datasource.embedded-postgres.instance-scope} to decide which of the
 * two embedded-Postgres bean pairs in {@link FlowableTestDatasourceAutoConfiguration} activates:
 * {@code per-context} (the default) forks a fresh native process per Spring context, {@code shared}
 * starts at most one process per JVM and provisions a fresh logical database per context on top of
 * it. {@link #isShared} throws {@link IllegalStateException} for any other value.
 *
 * <p>Exposed as two nested {@link Condition} implementations, {@link PerContext} and {@link
 * Shared}, so the corresponding {@code @Bean} methods remain mutually exclusive
 * {@code @Conditional} gates rather than a single method branching internally -- the same shape
 * used by {@link EmbeddedPostgresPreferredCondition}.
 */
final class EmbeddedPostgresInstanceScopeCondition {

  static final String INSTANCE_SCOPE_PROPERTY =
      "flowable.test.datasource.embedded-postgres.instance-scope";

  private EmbeddedPostgresInstanceScopeCondition() {}

  static boolean isShared(final Environment environment) {
    final String scope = environment.getProperty(INSTANCE_SCOPE_PROPERTY, "per-context");
    return switch (scope) {
      case "per-context" -> false;
      case "shared" -> true;
      default ->
          throw new IllegalStateException(
              "%s=%s is not supported -- expected 'per-context' or 'shared'."
                  .formatted(INSTANCE_SCOPE_PROPERTY, scope));
    };
  }

  static final class PerContext implements Condition {
    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
      return !isShared(context.getEnvironment());
    }
  }

  static final class Shared implements Condition {
    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
      return isShared(context.getEnvironment());
    }
  }
}
