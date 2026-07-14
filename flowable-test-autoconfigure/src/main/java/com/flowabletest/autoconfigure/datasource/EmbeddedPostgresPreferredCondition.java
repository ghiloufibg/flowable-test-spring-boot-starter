package com.flowabletest.autoconfigure.datasource;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether the embedded-Postgres {@code DataSource} beans in {@link
 * FlowableTestDatasourceAutoConfiguration} should activate, based on {@code
 * flowable.test.datasource.provider}: {@code embedded-postgres} forces it on, {@code h2} forces it
 * off even if {@code io.zonky.test:embedded-postgres} happens to be on the classpath, and the
 * default {@code auto} prefers it whenever it's present. This condition only runs once the bean
 * method's own {@code @ConditionalOnClass(EmbeddedPostgres.class)} has already confirmed the
 * library is present, so {@code auto} returning {@code true} here is exactly "use it because it's
 * there".
 *
 * <p>That auto-preference is a real footgun in a module that pulls in {@code embedded-postgres} for
 * only one test class: every other {@code @FlowableProcessTest} in the module silently flips from
 * H2 to Postgres too, the moment the dependency lands on the test classpath -- with no property
 * change and often a confusing downstream failure (e.g. a hardcoded H2 Hibernate dialect producing
 * DDL Postgres rejects) rather than an obvious "wrong datasource" error. The one-time warning below
 * exists specifically to surface that silent flip.
 */
final class EmbeddedPostgresPreferredCondition implements Condition {

  static final String PROVIDER_PROPERTY = "flowable.test.datasource.provider";

  private static final Logger log =
      LoggerFactory.getLogger(EmbeddedPostgresPreferredCondition.class);
  private static final AtomicBoolean AUTO_SELECTION_WARNED = new AtomicBoolean(false);

  @Override
  public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    final Environment environment = context.getEnvironment();
    final String provider = environment.getProperty(PROVIDER_PROPERTY, "auto");
    return switch (provider) {
      case "auto" -> {
        if (AUTO_SELECTION_WARNED.compareAndSet(false, true)) {
          log.warn(
              "{} is unset (defaulting to 'auto'), and io.zonky.test:embedded-postgres is on the "
                  + "test classpath -- every @FlowableProcessTest without its own explicit "
                  + "'{}=h2' now uses embedded Postgres instead of H2. Set it explicitly ('h2' or "
                  + "'embedded-postgres') to avoid this depending on which other test dependency "
                  + "happens to be present.",
              PROVIDER_PROPERTY,
              PROVIDER_PROPERTY);
        }
        yield true;
      }
      case "embedded-postgres" -> true;
      case "h2" -> false;
      default ->
          throw new IllegalStateException(
              "%s=%s is not supported -- expected 'auto', 'h2', or 'embedded-postgres'."
                  .formatted(PROVIDER_PROPERTY, provider));
    };
  }
}
