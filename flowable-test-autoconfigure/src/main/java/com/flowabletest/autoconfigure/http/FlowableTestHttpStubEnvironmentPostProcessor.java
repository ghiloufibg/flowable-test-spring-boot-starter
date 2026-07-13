package com.flowabletest.autoconfigure.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

/**
 * Starts one in-process WireMock server per service discovered via the default folder convention
 * and injects {@code <service-name>.base-url} into the {@code Environment} before the {@code
 * ApplicationContext} refreshes -- same timing rationale as the Kafka post-processor: a consumer's
 * {@code @ConfigurationProperties}-bound HTTP client may resolve that property while itself being
 * created during context refresh, so it must already be present.
 *
 * <p>This only handles the zero-code default path: scanning {@code
 * classpath:httpmocks/<name>/mappings/*.json} (design doc section 4.3). Per-test-class
 * {@code @MockExternalService} overrides are handled separately by {@link
 * MockExternalServiceContextCustomizer} -- see its Javadoc for why that requires a different Spring
 * TestContext extension point than this class.
 */
public final class FlowableTestHttpStubEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  private static final String PROPERTY_SOURCE_NAME = "flowableTestHttpMocks";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
      return;
    }
    if (!classPresent("com.github.tomakehurst.wiremock.WireMockServer")
        || !classPresent("org.flowable.engine.RuntimeService")) {
      return;
    }
    if (!environment.getProperty("flowable.test.http-mocks.enabled", Boolean.class, true)) {
      return;
    }

    final String root =
        environment.getProperty("flowable.test.http-mocks.root", "classpath:httpmocks");
    final HttpMockDiscovery discovery =
        new HttpMockDiscovery(new PathMatchingResourcePatternResolver());
    final Map<String, String> services =
        new LinkedHashMap<>(discovery.discoverDefaultServices(root));

    if (services.isEmpty()) {
      return;
    }

    final Map<String, Object> properties = new HashMap<>();
    for (final Map.Entry<String, String> entry : services.entrySet()) {
      final String name = entry.getKey();
      final String location = entry.getValue();
      final WireMockServer server = EmbeddedFlowableHttpMockSupport.ensureStarted(name, location);
      properties.put(name + ".base-url", "http://localhost:" + server.port());
    }
    properties.put(
        "flowable.test.http-mocks.discovered",
        services.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(",")));

    environment
        .getPropertySources()
        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  private static boolean classPresent(String className) {
    return ClassUtils.isPresent(
        className, FlowableTestHttpStubEnvironmentPostProcessor.class.getClassLoader());
  }
}
