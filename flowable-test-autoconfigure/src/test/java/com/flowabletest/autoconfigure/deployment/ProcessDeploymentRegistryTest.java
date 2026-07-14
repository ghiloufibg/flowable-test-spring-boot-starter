package com.flowabletest.autoconfigure.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves {@link ProcessDeploymentRegistry#encode(Map)} and {@link
 * ProcessDeploymentRegistry#resolve(org.springframework.core.env.Environment)} round-trip
 * correctly, and that {@code encode} fails fast on a name/location containing a delimiter character
 * it can't represent.
 */
class ProcessDeploymentRegistryTest {

  @Test
  void encodeThenResolveRoundTripsMultipleProcesses() {
    final Map<String, String> processes = new LinkedHashMap<>();
    processes.put("order-processing", "processes/order-processing.bpmn20.xml");
    processes.put("carrier-dispatch", "processes/carrier-dispatch.bpmn20.xml");

    final MockEnvironment environment = new MockEnvironment();
    environment.setProperty(
        "flowable.test.processes.discovered", ProcessDeploymentRegistry.encode(processes));

    assertThat(ProcessDeploymentRegistry.resolve(environment)).isEqualTo(processes);
  }

  @Test
  void resolveReturnsAnEmptyMapWhenNoPropertyIsSet() {
    assertThat(ProcessDeploymentRegistry.resolve(new MockEnvironment())).isEmpty();
  }

  @Test
  void encodeRejectsANameContainingAComma() {
    final Map<String, String> processes =
        Map.of("order,processing", "processes/order-processing.bpmn20.xml");

    assertThatIllegalStateException()
        .isThrownBy(() -> ProcessDeploymentRegistry.encode(processes))
        .withMessageContaining("order,processing");
  }

  @Test
  void encodeRejectsALocationContainingAnEqualsSign() {
    final Map<String, String> processes =
        Map.of("order-processing", "processes/order=processing.bpmn20.xml");

    assertThatIllegalStateException()
        .isThrownBy(() -> ProcessDeploymentRegistry.encode(processes))
        .withMessageContaining("order=processing.bpmn20.xml");
  }
}
