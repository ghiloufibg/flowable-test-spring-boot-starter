package com.flowabletest.autoconfigure.http;

import com.flowabletest.core.annotation.MockExternalService;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Redirects specific services to an alternate stub folder for one test class, per the {@code
 * @MockExternalService} annotations passed in {@code overrides} — the per-test escape hatch from
 * the classpath-wide default mapping folder.
 *
 * <p>Implemented as a {@link ContextCustomizer} rather than as logic inside {@link
 * FlowableTestHttpStubEnvironmentPostProcessor} because an {@code EnvironmentPostProcessor} has no
 * visibility into the JUnit test class, only the primary {@code @SpringBootTest(classes=...)}
 * source; and because {@code @MockExternalService} is not one of the annotation types Spring's
 * {@code MergedContextConfiguration} considers when deciding whether two test classes can share a
 * cached {@code ApplicationContext}, so two test classes with different overrides would otherwise
 * silently share one context and whichever ran first would "win" for both. {@code
 * ContextCustomizer} instances explicitly participate in that cache key via {@code equals}/{@code
 * hashCode}, which this record derives automatically from {@code overrides}, so a differing
 * override set correctly forces a separate context.
 *
 * <p>Runs during context preparation, before {@code refresh()}: late enough that it overwrites (via
 * {@code addFirst}) whatever base-url the default convention scan already set for the same service
 * name, but still early enough for property placeholder resolution during bean creation to see the
 * override.
 */
record MockExternalServiceContextCustomizer(Set<MockExternalService> overrides)
    implements ContextCustomizer {

  @Override
  public void customizeContext(
      ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
    final Map<String, Object> properties = new HashMap<>();
    final Map<String, String> overriddenLocations = new HashMap<>();
    for (final MockExternalService override : overrides) {
      final String location =
          override.stubs().isBlank()
              ? "httpmocks/" + override.name()
              : HttpMockDiscovery.stripClasspathPrefix(override.stubs());
      final WireMockServer server =
          EmbeddedFlowableHttpMockSupport.ensureStarted(override.name(), location);
      properties.put(override.name() + ".base-url", "http://localhost:" + server.port());
      overriddenLocations.put(override.name(), location);
    }
    properties.put(
        "flowable.test.http-mocks.overridden", HttpMockServiceRegistry.encode(overriddenLocations));
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("flowableTestHttpMocksOverride", properties));
  }
}
