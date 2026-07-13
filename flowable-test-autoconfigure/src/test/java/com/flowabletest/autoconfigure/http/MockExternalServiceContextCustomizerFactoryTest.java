package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.core.annotation.MockExternalService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link MockExternalServiceContextCustomizerFactory} fails fast when a test class declares
 * {@code @MockExternalService} for the same service name more than once with different {@code
 * stubs} folders -- previously, both survived into the customizer's {@code Set}, and whichever the
 * {@code Set}/{@code Map} iteration order happened to process last silently won, a
 * non-deterministic outcome across JVM runs rather than an explicit configuration error.
 */
class MockExternalServiceContextCustomizerFactoryTest {

  @MockExternalService(name = "dup-service", stubs = "classpath:httpmocks/demo-service")
  @MockExternalService(name = "dup-service", stubs = "classpath:httpmocks-alt/demo-service")
  private static class ConflictingOverridesTestClass {}

  private final MockExternalServiceContextCustomizerFactory factory =
      new MockExternalServiceContextCustomizerFactory();

  @Test
  void throwsWhenTheSameServiceNameIsOverriddenTwiceWithDifferentStubs() {
    assertThatThrownBy(
            () -> factory.createContextCustomizer(ConflictingOverridesTestClass.class, List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dup-service")
        .hasMessageContaining(ConflictingOverridesTestClass.class.getName());
  }

  @Test
  void returnsNullWhenNoOverrideIsPresent() {
    assertThat(factory.createContextCustomizer(Object.class, List.of())).isNull();
  }
}
