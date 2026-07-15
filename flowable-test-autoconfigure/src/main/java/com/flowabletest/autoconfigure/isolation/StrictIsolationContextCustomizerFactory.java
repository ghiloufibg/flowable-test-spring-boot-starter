package com.flowabletest.autoconfigure.isolation;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.FlowableTestIsolation;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * {@link ContextCustomizerFactory} that activates {@link StrictIsolationContextCustomizer} for test
 * classes annotated with {@link FlowableProcessTest} whose {@link FlowableTestIsolation} is {@code
 * SEPARATE_CONTEXT}. Registered via {@code META-INF/spring.factories} under {@link
 * ContextCustomizerFactory}.
 */
public final class StrictIsolationContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    final FlowableProcessTest annotation =
        AnnotatedElementUtils.findMergedAnnotation(testClass, FlowableProcessTest.class);
    if (annotation == null || annotation.isolation() != FlowableTestIsolation.SEPARATE_CONTEXT) {
      return null;
    }
    return new StrictIsolationContextCustomizer(testClass.getName());
  }
}
