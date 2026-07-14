package com.flowabletest.autoconfigure.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
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
 *
 * <p>{@link #encode(Map)} is the single shared counterpart used by both writers of these properties
 * ({@link FlowableTestHttpStubEnvironmentPostProcessor} and {@link
 * MockExternalServiceContextCustomizer}), so the {@code "name=location,name2=location2"} wire
 * format is only ever produced and parsed in this one place.
 */
final class HttpMockServiceRegistry {

  private HttpMockServiceRegistry() {}

  static Map<String, String> resolve(Environment environment) {
    final Map<String, String> resolved = new LinkedHashMap<>();
    parseInto(resolved, environment.getProperty("flowable.test.http-mocks.discovered", ""));
    parseInto(resolved, environment.getProperty("flowable.test.http-mocks.overridden", ""));
    return Map.copyOf(resolved);
  }

  /**
   * Encodes a {@code name -> classpathLocation} map as the {@code "name=location,..."} string
   * stashed in an {@code Environment} property, for {@link #resolve(Environment)} to later parse
   * back apart. Fails fast if any name or location contains a {@code ','} or {@code '='} -- either
   * would be indistinguishable from a field/entry separator once encoded, silently corrupting the
   * round trip (a service simply wouldn't resolve, with no obvious cause) rather than failing
   * loudly at the point the bad name was actually introduced.
   */
  static String encode(Map<String, String> services) {
    services.forEach(HttpMockServiceRegistry::validateNoDelimiterCharacters);
    return services.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(","));
  }

  private static void validateNoDelimiterCharacters(String name, String location) {
    if (containsDelimiter(name)) {
      throw new IllegalStateException(
          "HTTP mock service name '"
              + name
              + "' contains a ',' or '=' character, which this starter's internal property "
              + "encoding can't represent -- rename the service folder (or the "
              + "@MockExternalService `name`) to avoid these characters.");
    }
    if (containsDelimiter(location)) {
      throw new IllegalStateException(
          "HTTP mock service location '"
              + location
              + "' (for service '"
              + name
              + "') contains a ',' or '=' character, which this starter's internal property "
              + "encoding can't represent -- rename the folder (or the @MockExternalService "
              + "`stubs` path) to avoid these characters.");
    }
  }

  private static boolean containsDelimiter(String value) {
    return value.indexOf(',') >= 0 || value.indexOf('=') >= 0;
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
