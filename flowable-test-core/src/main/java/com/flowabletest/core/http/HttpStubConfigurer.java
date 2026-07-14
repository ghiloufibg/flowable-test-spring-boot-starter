package com.flowabletest.core.http;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Extension point for HTTP stubs that can't be expressed as static JSON mapping files (e.g. a
 * response that must echo something computed at test-startup time). Implementations are registered
 * as Spring beans; the auto-configuration that wires up HTTP mocks invokes every configurer bean
 * once per discovered service, after that service's declarative WireMock mapping JSON files have
 * already been loaded -- so most implementations only need to add one dynamic stub on top of the
 * declarative ones, not rebuild the whole set. A {@link WireMockServer} shared across multiple test
 * contexts is configured only once, not once per context reusing it.
 *
 * <p>This is intentionally the last-resort option; the default, zero-code path is dropping WireMock
 * mapping JSON files under {@code classpath:httpmocks/<service-name>/mappings/}.
 */
public interface HttpStubConfigurer {

  void configure(String serviceName, WireMockServer server);
}
