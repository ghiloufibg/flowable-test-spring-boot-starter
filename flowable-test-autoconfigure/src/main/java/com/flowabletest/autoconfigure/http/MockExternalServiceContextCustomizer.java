package com.flowabletest.autoconfigure.http;

import com.flowabletest.core.annotation.MockExternalService;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Redirects specific services to an alternate stub folder for one test class (design doc section
 * 4.3's escape hatch). Deliberately a {@link ContextCustomizer} rather than logic inside {@link
 * FlowableTestHttpStubEnvironmentPostProcessor}: an {@code EnvironmentPostProcessor} has no
 * visibility into the JUnit test class (only the primary {@code @SpringBootTest(classes=...)}
 * source), and even if it did, {@code @MockExternalService} isn't one of the annotation types
 * Spring's {@code MergedContextConfiguration} considers when deciding whether two test classes can
 * share a cached {@code ApplicationContext} -- so two test classes with different overrides would
 * otherwise silently share one context and whichever ran first would "win" for both. {@code
 * ContextCustomizer} instances explicitly participate in that cache key via {@link
 * #equals(Object)}/{@link #hashCode()}, so a differing override set correctly forces a separate
 * context.
 *
 * <p>Runs during context preparation, before {@code refresh()} -- late enough that this overwrites
 * (via {@code addFirst}) whatever base-url the default convention scan already set for the same
 * service name, but still early enough for property placeholder resolution during bean creation to
 * see the override.
 */
final class MockExternalServiceContextCustomizer implements ContextCustomizer {

  private final Set<MockExternalService> overrides;

  MockExternalServiceContextCustomizer(Set<MockExternalService> overrides) {
    this.overrides = overrides;
  }

  @Override
  public void customizeContext(
      ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
    Map<String, Object> properties = new HashMap<>();
    for (MockExternalService override : overrides) {
      String location =
          override.stubs().isBlank()
              ? "httpmocks/" + override.name()
              : HttpMockDiscovery.stripClasspathPrefix(override.stubs());
      WireMockServer server =
          EmbeddedFlowableHttpMockSupport.startIfNeeded(override.name(), location);
      properties.put(override.name() + ".base-url", "http://localhost:" + server.port());
    }
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("flowableTestHttpMocksOverride", properties));
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof MockExternalServiceContextCustomizer other
        && overrides.equals(other.overrides);
  }

  @Override
  public int hashCode() {
    return Objects.hash(overrides);
  }
}
