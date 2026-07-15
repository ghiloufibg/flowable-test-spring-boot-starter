package com.flowabletest.core.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;

/**
 * Wraps the {@link WireMockServer} instances started for each discovered service, keyed by service
 * name, as a single injectable bean. A dedicated record rather than a bare {@code Map<String,
 * WireMockServer>} bean, because Spring's {@code @Autowired} resolution treats an injection point
 * of generic type {@code Map<String, X>} as "collect every bean of type X, keyed by bean name"
 * rather than "find the one bean whose own type happens to be that Map" -- so a directly-exposed
 * Map bean would never be found by a consumer autowiring that generic type.
 */
public record HttpMockServers(Map<String, WireMockServer> servers) {

  public HttpMockServers {
    servers = Map.copyOf(servers);
  }

  public WireMockServer get(String serviceName) {
    return servers.get(serviceName);
  }

  public Map<String, WireMockServer> asMap() {
    return servers;
  }
}
