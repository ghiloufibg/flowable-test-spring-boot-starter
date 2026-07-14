/**
 * Types shared between declarative HTTP mocking's default, zero-code path and its escape hatches.
 * The default path -- plain WireMock mapping JSON under {@code
 * classpath:httpmocks/<service-name>/mappings/*.json}, auto-discovered and started one server per
 * service -- lives in {@code flowable-test-autoconfigure}, since it needs Spring auto-configuration
 * machinery. This package only holds what a consumer's own code touches directly: {@link
 * com.flowabletest.core.http.HttpMockServers} (the autowired {@code <service-name>, WireMockServer}
 * lookup) and {@link com.flowabletest.core.http.HttpStubConfigurer} (the last-resort SPI for a stub
 * that can't be expressed as a static JSON file).
 */
package com.flowabletest.core.http;
