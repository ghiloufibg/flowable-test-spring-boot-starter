package com.flowabletest.autoconfigure.deployment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;

/**
 * Encodes/decodes the {@code name -> classpathLocation} map {@link
 * FlowableTestProcessDeploymentEnvironmentPostProcessor} resolves into a single, comma/equals-
 * delimited {@code Environment} property (mirroring {@code HttpMockServiceRegistry}'s encoding
 * idiom), so {@link FlowableTestProcessDeploymentAutoConfiguration}'s deployment bean can read it
 * back once {@code RepositoryService} exists. {@link #encode} rejects any name or location
 * containing a delimiter character.
 */
final class ProcessDeploymentRegistry {

  private static final String PROPERTY_KEY = "flowable.test.processes.discovered";

  private ProcessDeploymentRegistry() {}

  static Map<String, String> resolve(Environment environment) {
    final Map<String, String> resolved = new LinkedHashMap<>();
    parseInto(resolved, environment.getProperty(PROPERTY_KEY, ""));
    return Map.copyOf(resolved);
  }

  static String encode(Map<String, String> processes) {
    processes.forEach(ProcessDeploymentRegistry::validateNoDelimiterCharacters);
    return processes.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(","));
  }

  private static void validateNoDelimiterCharacters(String name, String location) {
    if (containsDelimiter(name) || containsDelimiter(location)) {
      throw new IllegalStateException(
          "Process name or classpath location contains a ',' or '=' character, which conflicts "
              + "with the encoding used to pass discovered processes between the "
              + "EnvironmentPostProcessor and the deployment bean: name='"
              + name
              + "', location='"
              + location
              + "'");
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
