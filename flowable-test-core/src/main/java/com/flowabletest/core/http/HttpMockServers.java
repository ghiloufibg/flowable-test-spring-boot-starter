package com.flowabletest.core.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;

/**
 * Deliberately a dedicated type rather than a bare {@code Map<String, WireMockServer>} bean:
 * Spring's {@code @Autowired} resolution treats an injection point of generic type {@code
 * Map<String, X>} as "collect every bean of type X, keyed by bean name" rather than "find the one
 * bean whose own type happens to be that Map" -- so a directly-exposed Map bean is never found by
 * consumers autowiring that generic type. Wrapping it sidesteps that Spring behavior entirely.
 */
public final class HttpMockServers {

  private final Map<String, WireMockServer> servers;

  public HttpMockServers(Map<String, WireMockServer> servers) {
    this.servers = Map.copyOf(servers);
  }

  public WireMockServer get(String serviceName) {
    return servers.get(serviceName);
  }

  public Map<String, WireMockServer> asMap() {
    return servers;
  }
}
