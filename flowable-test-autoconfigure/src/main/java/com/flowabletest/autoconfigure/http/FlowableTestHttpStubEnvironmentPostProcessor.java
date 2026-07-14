package com.flowabletest.autoconfigure.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
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
 * classpath:httpmocks/<name>/mappings/*.json}. Per-test-class {@code @MockExternalService}
 * overrides are handled separately by {@link MockExternalServiceContextCustomizer} -- see its
 * Javadoc for why that requires a different Spring TestContext extension point than this class.
 *
 * <p>{@code flowable.test.http-mocks.services} (optional, opt-in) replaces the scan with an
 * explicit, declared list of service names. Absent (the default), behavior is unchanged: every
 * immediate subfolder under {@code root} is discovered and started, exactly as the plain scan
 * always has. Declared, it becomes the sole source of which services start here; folders on the
 * classpath that aren't declared are left alone, and a declared name with no matching {@code
 * mappings} folder fails fast, right here, before the {@code ApplicationContext} starts refreshing.
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
    final List<String> declaredServices =
        Binder.get(environment)
            .bind("flowable.test.http-mocks.services", Bindable.listOf(String.class))
            .orElse(List.of());
    final Map<String, String> services =
        new LinkedHashMap<>(
            declaredServices.isEmpty()
                ? discovery.discoverDefaultServices(root)
                : discovery.resolveDeclaredServices(root, declaredServices));

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
    properties.put("flowable.test.http-mocks.discovered", HttpMockServiceRegistry.encode(services));

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
