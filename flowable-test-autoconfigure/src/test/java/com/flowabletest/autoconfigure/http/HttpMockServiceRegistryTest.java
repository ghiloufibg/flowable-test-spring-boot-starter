package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves {@link HttpMockServiceRegistry#encode(Map)} and {@link
 * HttpMockServiceRegistry#resolve(org.springframework.core.env.Environment)} round-trip correctly,
 * and that {@code encode} fails fast on a name/location containing a delimiter character it can't
 * represent -- rather than silently producing a corrupted property that would resolve into the
 * wrong (or a missing) service downstream.
 */
class HttpMockServiceRegistryTest {

  @Test
  void encodeThenResolveRoundTripsMultipleServices() {
    final Map<String, String> services = new LinkedHashMap<>();
    services.put("payment-gateway", "httpmocks/payment-gateway");
    services.put("fraud-check-service", "httpmocks/fraud-check-service");

    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "flowable.test.http-mocks.discovered", HttpMockServiceRegistry.encode(services));

    assertThat(HttpMockServiceRegistry.resolve(environment)).isEqualTo(services);
  }

  @Test
  void overriddenPropertyWinsOverDiscoveredForTheSameName() {
    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "flowable.test.http-mocks.discovered", "payment-gateway=httpmocks/payment-gateway");
    environment.setProperty(
        "flowable.test.http-mocks.overridden", "payment-gateway=httpmocks/payment-gateway-timeout");

    assertThat(HttpMockServiceRegistry.resolve(environment))
        .containsEntry("payment-gateway", "httpmocks/payment-gateway-timeout");
  }

  @Test
  void encodeRejectsAServiceNameContainingAComma() {
    final Map<String, String> services = Map.of("payment,gateway", "httpmocks/payment-gateway");

    assertThatIllegalStateException()
        .isThrownBy(() -> HttpMockServiceRegistry.encode(services))
        .withMessageContaining("payment,gateway");
  }

  @Test
  void encodeRejectsAServiceNameContainingAnEqualsSign() {
    final Map<String, String> services = Map.of("payment=gateway", "httpmocks/payment-gateway");

    assertThatIllegalStateException()
        .isThrownBy(() -> HttpMockServiceRegistry.encode(services))
        .withMessageContaining("payment=gateway");
  }

  @Test
  void encodeRejectsALocationContainingADelimiterCharacter() {
    final Map<String, String> services = Map.of("payment-gateway", "httpmocks/payment,gateway");

    assertThatIllegalStateException()
        .isThrownBy(() -> HttpMockServiceRegistry.encode(services))
        .withMessageContaining("httpmocks/payment,gateway");
  }
}
