package com.flowabletest.autoconfigure.http;

import com.flowabletest.core.annotation.MockExternalService;
import java.util.List;
import java.util.Set;
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
public class MockExternalServiceContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    Set<MockExternalService> overrides =
        AnnotatedElementUtils.findMergedRepeatableAnnotations(testClass, MockExternalService.class);
    return overrides.isEmpty() ? null : new MockExternalServiceContextCustomizer(overrides);
  }
}
