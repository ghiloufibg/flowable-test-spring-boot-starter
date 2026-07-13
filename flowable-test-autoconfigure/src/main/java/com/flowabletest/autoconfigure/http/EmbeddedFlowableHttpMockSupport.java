package com.flowabletest.autoconfigure.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts (at most once per name+location combination, per JVM) the in-process WireMock servers
 * discovered by {@link FlowableTestHttpStubEnvironmentPostProcessor}. Same rationale as {@code
 * EmbeddedFlowableKafkaSupport}: this has to run before an {@code ApplicationContext} exists, so it
 * can't be a Spring bean itself.
 *
 * <p>Keyed by {@code name + "|" + classpathLocation} rather than name alone, so a test overriding a
 * service's stub folder via {@code @MockExternalService(stubs = ...)} gets its own server rather
 * than reusing one already loaded with different mappings.
 */
final class EmbeddedFlowableHttpMockSupport {

  private static final Map<String, WireMockServer> SERVERS = new ConcurrentHashMap<>();

  private EmbeddedFlowableHttpMockSupport() {}

  static WireMockServer startIfNeeded(String name, String classpathLocation) {
    return SERVERS.computeIfAbsent(
        key(name, classpathLocation),
        k -> {
          final WireMockServer server =
              new WireMockServer(
                  WireMockConfiguration.options()
                      .dynamicPort()
                      .usingFilesUnderClasspath(classpathLocation));
          server.start();
          Runtime.getRuntime()
              .addShutdownHook(new Thread(server::stop, "flowable-test-httpmock-shutdown-" + name));
          return server;
        });
  }

  static WireMockServer get(String name, String classpathLocation) {
    return SERVERS.get(key(name, classpathLocation));
  }

  private static String key(String name, String classpathLocation) {
    return name + "|" + classpathLocation;
  }
}
