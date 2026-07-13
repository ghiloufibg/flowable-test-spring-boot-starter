package com.flowabletest.autoconfigure.kafka;

import com.flowabletest.core.annotation.EmbeddedFlowableKafka;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Applies {@link EmbeddedFlowableKafka#additionalTopics()} (and, only if the broker hasn't started
 * yet, {@link EmbeddedFlowableKafka#partitions()}) for one test class (design doc section 4.2's
 * fine-tuning escape hatch). Deliberately a {@link ContextCustomizer} rather than logic inside
 * {@link FlowableTestKafkaEnvironmentPostProcessor}: an {@code EnvironmentPostProcessor} has no
 * visibility into the JUnit test class (only the primary {@code @SpringBootTest(classes=...)}
 * source) -- same rationale as {@code MockExternalServiceContextCustomizer}.
 *
 * <p>The embedded broker is a JVM-wide singleton ({@link EmbeddedFlowableKafkaSupport}, started at
 * most once per JVM). If the environment post-processor (or an earlier test class in the same JVM)
 * already started it, {@link EmbeddedFlowableKafka#partitions()} can't retroactively change the
 * broker's own startup partition count -- so it's applied per-topic to this annotation's own {@code
 * additionalTopics()} instead, via the broker's {@code addTopics(NewTopic...)}, which works against
 * an already-running broker. If nothing has started the broker yet (no Event Registry channels on
 * the classpath), this customizer starts it itself using {@code additionalTopics()} as the initial
 * topic set and {@code partitions()} as the broker's partition count, injecting {@code
 * spring.kafka.bootstrap-servers} the same way the environment post-processor would have.
 */
record EmbeddedFlowableKafkaContextCustomizer(EmbeddedFlowableKafka annotation)
    implements ContextCustomizer {

  private static final String PROPERTY_SOURCE_NAME = "flowableTestEmbeddedKafkaAnnotationOverride";

  @Override
  public void customizeContext(
      ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
    final EmbeddedKafkaBroker existing = EmbeddedFlowableKafkaSupport.current();
    if (existing == null) {
      startBrokerFromAnnotation(context);
      return;
    }
    addMissingTopics(existing);
  }

  private void startBrokerFromAnnotation(ConfigurableApplicationContext context) {
    if (annotation.additionalTopics().length == 0) {
      return;
    }
    final Set<String> topics = new LinkedHashSet<>(Arrays.asList(annotation.additionalTopics()));
    final EmbeddedKafkaBroker started =
        EmbeddedFlowableKafkaSupport.startIfNeeded(topics, annotation.partitions());
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                PROPERTY_SOURCE_NAME,
                Map.of("spring.kafka.bootstrap-servers", started.getBrokersAsString())));
  }

  private void addMissingTopics(EmbeddedKafkaBroker broker) {
    final Set<String> existingTopics = broker.getTopics();
    final NewTopic[] topicsToAdd =
        Arrays.stream(annotation.additionalTopics())
            .filter(topic -> !existingTopics.contains(topic))
            .map(topic -> new NewTopic(topic, annotation.partitions(), (short) 1))
            .toArray(NewTopic[]::new);
    if (topicsToAdd.length > 0) {
      broker.addTopics(topicsToAdd);
    }
  }
}
