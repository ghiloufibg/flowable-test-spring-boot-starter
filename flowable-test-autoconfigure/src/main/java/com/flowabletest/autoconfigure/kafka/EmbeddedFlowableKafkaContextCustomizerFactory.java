package com.flowabletest.autoconfigure.kafka;

import com.flowabletest.core.annotation.EmbeddedFlowableKafka;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * {@link ContextCustomizerFactory} that activates {@link EmbeddedFlowableKafkaContextCustomizer}
 * for test classes annotated with {@link EmbeddedFlowableKafka}. Registered via {@code
 * META-INF/spring.factories} under {@link ContextCustomizerFactory}.
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
