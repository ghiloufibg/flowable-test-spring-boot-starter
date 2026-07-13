package com.flowabletest.autoconfigure.http;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;

/**
 * Computes a Spring context's final, override-applied set of {@code name -> classpathLocation} HTTP
 * mock service mappings, merging the classpath-wide defaults discovered by {@link
 * FlowableTestHttpStubEnvironmentPostProcessor} ({@code flowable.test.http-mocks.discovered}) with
 * any per-test-class {@code @MockExternalService} overrides applied by {@link
 * MockExternalServiceContextCustomizer} ({@code flowable.test.http-mocks.overridden}) -- override
 * wins per service name.
 *
 * <p>Both the {@code httpMockServers} bean and the release listener in {@link
 * FlowableTestHttpStubAutoConfiguration} must resolve the exact same map for a given context, so
 * this is the single shared place that computation happens -- resolving it independently in two
 * places would risk the two call sites drifting (e.g. one seeing an override property the other
 * missed because it read the environment at a different point in the context lifecycle).
 */
final class HttpMockServiceRegistry {

  private HttpMockServiceRegistry() {}

  static Map<String, String> resolve(Environment environment) {
    final Map<String, String> resolved = new LinkedHashMap<>();
    parseInto(resolved, environment.getProperty("flowable.test.http-mocks.discovered", ""));
    parseInto(resolved, environment.getProperty("flowable.test.http-mocks.overridden", ""));
    return Map.copyOf(resolved);
  }

  private static void parseInto(Map<String, String> target, String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return;
    }
    for (final String pair : encoded.split(",")) {
      final String[] parts = pair.split("=", 2);
      if (parts.length == 2) {
        target.put(parts[0], parts[1]);
      }
    }
  }
}
