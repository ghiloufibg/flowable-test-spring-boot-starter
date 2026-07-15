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
 * {@link ContextCustomizer} that applies {@link EmbeddedFlowableKafka#additionalTopics()} and
 * {@link EmbeddedFlowableKafka#partitions()} for one test class. A {@link ContextCustomizer} is
 * used rather than folding this into {@link FlowableTestKafkaEnvironmentPostProcessor} because an
 * {@code EnvironmentPostProcessor} has no visibility into the JUnit test class itself.
 *
 * <p>The embedded broker ({@link EmbeddedFlowableKafkaSupport}) is started at most once per JVM. If
 * it is already running -- started by the post-processor or an earlier test class -- {@code
 * partitions()} can no longer change the broker's own startup partition count, so only the missing
 * {@code additionalTopics()} are added to the running broker. If nothing has started the broker
 * yet, this customizer starts it itself using {@code additionalTopics()} as the initial topic set
 * and {@code partitions()} as the broker's partition count, then injects {@code
 * spring.kafka.bootstrap-servers} the same way the post-processor would have.
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
