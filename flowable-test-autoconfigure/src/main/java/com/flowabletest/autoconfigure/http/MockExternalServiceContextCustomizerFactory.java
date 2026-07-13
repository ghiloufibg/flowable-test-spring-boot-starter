package com.flowabletest.autoconfigure.http;

import com.flowabletest.core.annotation.MockExternalService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * Registered via {@code META-INF/spring.factories} under {@code
 * org.springframework.test.context.ContextCustomizerFactory} -- see {@link
 * MockExternalServiceContextCustomizer} for why this SPI, not an {@code EnvironmentPostProcessor},
 * is what implements {@link MockExternalService}.
 */
public final class MockExternalServiceContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    final Set<MockExternalService> overrides =
        AnnotatedElementUtils.findMergedRepeatableAnnotations(testClass, MockExternalService.class);
    if (overrides.isEmpty()) {
      return null;
    }
    failFastOnConflictingNames(testClass, overrides);
    return new MockExternalServiceContextCustomizer(overrides);
  }

  /**
   * {@code overrides} is a {@code Set}, so two {@code @MockExternalService} annotations declaring
   * the same {@code name} with different {@code stubs} would otherwise both survive into it (they
   * differ by {@code stubs}, so they're unequal elements) and silently race for which one's {@code
   * <name>.base-url} property wins -- {@code Set}/{@code Map} iteration order isn't a contract, so
   * that race isn't just wrong, it's non-deterministic across JVM runs.
   */
  private static void failFastOnConflictingNames(
      Class<?> testClass, Set<MockExternalService> overrides) {
    final List<String> conflicting =
        overrides.stream()
            .collect(Collectors.groupingBy(MockExternalService::name))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .toList();
    if (!conflicting.isEmpty()) {
      throw new IllegalStateException(
          "%s declares @MockExternalService for the same service name more than once with "
                  .formatted(testClass.getName())
              + "different `stubs` values: "
              + conflicting
              + " -- remove the duplicate(s), only one `stubs` folder can win per service name.");
    }
  }
}
