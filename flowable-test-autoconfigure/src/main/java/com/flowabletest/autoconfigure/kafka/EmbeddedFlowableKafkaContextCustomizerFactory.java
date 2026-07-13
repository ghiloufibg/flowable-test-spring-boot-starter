package com.flowabletest.autoconfigure.kafka;

import com.flowabletest.core.annotation.EmbeddedFlowableKafka;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * Registered via {@code META-INF/spring.factories} under {@code
 * org.springframework.test.context.ContextCustomizerFactory} -- see {@link
 * EmbeddedFlowableKafkaContextCustomizer} for why this SPI, not an {@code
 * EnvironmentPostProcessor}, is what applies {@link EmbeddedFlowableKafka}.
 */
public final class EmbeddedFlowableKafkaContextCustomizerFactory
    implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
    final EmbeddedFlowableKafka annotation =
        AnnotatedElementUtils.findMergedAnnotation(testClass, EmbeddedFlowableKafka.class);
    return annotation == null ? null : new EmbeddedFlowableKafkaContextCustomizer(annotation);
  }
}
