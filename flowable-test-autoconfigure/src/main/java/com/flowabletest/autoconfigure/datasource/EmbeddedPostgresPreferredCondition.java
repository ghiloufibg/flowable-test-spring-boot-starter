package com.flowabletest.autoconfigure.datasource;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether the embedded-Postgres {@code DataSource} beans in {@link
 * FlowableTestDatasourceAutoConfiguration} should activate, based on {@code
 * flowable.test.datasource.provider}: {@code embedded-postgres} forces it
 * on, {@code h2} forces it off even if {@code io.zonky.test:embedded-postgres} happens to be on the
 * classpath, and the default {@code auto} prefers it whenever it's present. This condition only
 * runs once the bean method's own {@code @ConditionalOnClass(EmbeddedPostgres.class)} has already
 * confirmed the library is present, so {@code auto} returning {@code true} here is exactly "use it
 * because it's there".
 */
final class EmbeddedPostgresPreferredCondition implements Condition {

  static final String PROVIDER_PROPERTY = "flowable.test.datasource.provider";

  @Override
  public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    final Environment environment = context.getEnvironment();
    final String provider = environment.getProperty(PROVIDER_PROPERTY, "auto");
    return switch (provider) {
      case "auto", "embedded-postgres" -> true;
      case "h2" -> false;
      default ->
          throw new IllegalStateException(
              "%s=%s is not supported -- expected 'auto', 'h2', or 'embedded-postgres'."
                  .formatted(PROVIDER_PROPERTY, provider));
    };
  }
}
