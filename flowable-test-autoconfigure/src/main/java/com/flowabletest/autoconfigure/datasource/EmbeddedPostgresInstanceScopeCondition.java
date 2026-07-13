package com.flowabletest.autoconfigure.datasource;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether the embedded-Postgres {@code DataSource} bean pair in {@link
 * FlowableTestDatasourceAutoConfiguration} forks a fresh native process per Spring context ({@code
 * per-context}, the default) or starts at most one process per JVM and provisions a fresh logical
 * database per context on top of it ({@code shared}) -- see {@code
 * claudedocs/embedded-postgres-instance-scope-design.md}. Same shape as {@link
 * EmbeddedPostgresPreferredCondition}: a small helper plus two trivial {@link Condition}
 * implementations, so the two bean methods stay mutually exclusive {@code @Conditional} gates
 * rather than one method branching internally -- keeping the {@code per-context} bean definition
 * byte-for-byte what it was before this property existed.
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
