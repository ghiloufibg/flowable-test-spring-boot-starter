package com.flowabletest.core.http;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Escape hatch for stubs that can't be expressed as static JSON files (e.g. a response that must
 * echo something computed at test-startup time). Implement this on a test class and it is
 * invoked once per discovered service, after the plain JSON mapping files under that service's
 * folder have already been loaded -- so most implementations only need to add one dynamic stub
 * on top of the declarative ones, not rebuild the whole set.
 *
 * <p>This is intentionally the last-resort option; the default, zero-code path is dropping
 * WireMock mapping JSON files under {@code classpath:httpmocks/<service-name>/mappings/}.
 */
public interface HttpStubConfigurer {

    void configure(String serviceName, WireMockServer server);
}
