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
 * {@link ContextCustomizer} that forces a {@code SEPARATE_CONTEXT}-isolated test class to never
 * share a cached {@code ApplicationContext} with any other test class. As a record, its {@code
 * equals}/{@code hashCode} are derived from {@link #testClassName}, which is always unique per test
 * class, so Spring's {@code TestContextCache} key never matches an existing entry and a brand-new
 * context is built every time.
 *
 * <p>{@link #customizeContext} additionally guarantees the new context gets a genuinely separate
 * <em>database</em>, not just a separate {@code ProcessEngine}: the embedded-postgres provider
 * already isolates per context, but H2 does not, so a fixed {@code spring.datasource.url} (e.g.
 * {@code jdbc:h2:mem:testdb}) would otherwise let two "isolated" contexts share the same physical
 * database for the JVM's lifetime. Whenever H2 is the active provider, this unconditionally
 * overrides {@code spring.datasource.url} with a freshly generated, uniquely named in-memory URL
 * before the context refreshes.
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
