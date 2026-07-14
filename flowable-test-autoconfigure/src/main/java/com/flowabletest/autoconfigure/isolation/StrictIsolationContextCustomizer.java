package com.flowabletest.autoconfigure.isolation;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.util.ClassUtils;

/**
 * Forces {@code isolation = SEPARATE_CONTEXT} classes to never share a cached {@code
 * ApplicationContext} with any other test class -- including another {@code SEPARATE_CONTEXT}
 * class. A record's {@code equals}/{@code hashCode} are derived from every component, so two
 * instances are equal only when {@code testClassName} matches; since this customizer is keyed by
 * the declaring class's own (always-unique) name, no other class's cache key can ever match it,
 * which forces Spring to build a brand-new context every time this class runs. Same technique
 * {@code @DirtiesContext} uses internally, but scoped to poisoning only this class's own cache key
 * rather than also forcing a rebuild for whatever runs after it.
 *
 * <p>{@code customizeContext} additionally guarantees this brand-new context also gets a genuinely
 * separate <em>database</em>, not just a separate {@code ProcessEngine}: the embedded-postgres
 * provider already isolates for free (a real native process or logical database per context), but
 * H2 does not -- left alone, H2 is entirely Spring Boot's own {@code DataSourceAutoConfiguration}
 * default, so a consumer who pins a fixed {@code spring.datasource.url} (a common pattern, e.g.
 * {@code jdbc:h2:mem:testdb}) would otherwise get two distinct engines that still share the exact
 * same physical database for the JVM's lifetime. This unconditionally overrides {@code
 * spring.datasource.url} with a fresh, uniquely-named in-memory URL whenever H2 is the active
 * provider -- winning even over the consumer's own configuration, the same way this starter's
 * process-deployment allow-list already wins over {@code flowable.check-process-definitions} --
 * because "separate context" that still shares a database isn't actually separate. Runs after this
 * starter's {@code EnvironmentPostProcessor}s (so {@code flowable.test.datasource.provider} is
 * already resolved) and before the context refreshes (so {@code DataSourceAutoConfiguration} still
 * sees the override).
 */
record StrictIsolationContextCustomizer(String testClassName) implements ContextCustomizer {

  private static final String DATASOURCE_PROVIDER_PROPERTY = "flowable.test.datasource.provider";
  private static final String EMBEDDED_POSTGRES_CLASS =
      "io.zonky.test.db.postgres.embedded.EmbeddedPostgres";

  @Override
  public void customizeContext(
      ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
    final ConfigurableEnvironment environment = context.getEnvironment();
    if (embeddedPostgresWillBeUsed(environment)) {
      return;
    }
    final String isolatedUrl =
        "jdbc:h2:mem:flowable-test-isolated-"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false";
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "flowableTestStrictIsolationDatasource",
                Map.of("spring.datasource.url", isolatedUrl)));
  }

  private static boolean embeddedPostgresWillBeUsed(ConfigurableEnvironment environment) {
    final String provider = environment.getProperty(DATASOURCE_PROVIDER_PROPERTY, "auto");
    return switch (provider) {
      case "auto", "embedded-postgres" ->
          ClassUtils.isPresent(
              EMBEDDED_POSTGRES_CLASS, StrictIsolationContextCustomizer.class.getClassLoader());
      default -> false; // "h2", or an invalid value that fails fast elsewhere
    };
  }
}
