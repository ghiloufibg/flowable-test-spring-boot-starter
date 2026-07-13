package com.flowabletest.autoconfigure.kafka;

import com.flowabletest.core.kafka.KafkaTestBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Exposes the broker started by {@link FlowableTestKafkaEnvironmentPostProcessor} as an ordinary
 * bean (for consumers who want {@code @Autowired EmbeddedKafkaBroker}), and registers {@link
 * KafkaTestBridge} pointed at it. Both beans are conditional on {@code
 * spring.kafka.bootstrap-servers} actually being set by the post-processor -- if no Kafka Event
 * Registry channel descriptors were found on the classpath, neither activates.
 */
@AutoConfiguration
@ConditionalOnClass(value = EmbeddedKafkaBroker.class, name = "org.flowable.engine.RuntimeService")
public class FlowableTestKafkaAutoConfiguration {

  /**
   * {@code destroyMethod = ""} disables Spring's default destroy-method inference (which would
   * otherwise call {@code EmbeddedKafkaBroker.destroy()} when *this* context closes). The broker is
   * a JVM-wide singleton shared across every context that discovers Kafka Event Registry channels
   * ({@link EmbeddedFlowableKafkaSupport}, started at most once per JVM) and already has its own
   * JVM shutdown hook -- letting each context additionally destroy it on close causes a second,
   * failing shutdown attempt against an already-stopped broker.
   */
  @Bean(destroyMethod = "")
  @ConditionalOnMissingBean
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  EmbeddedKafkaBroker embeddedKafkaBroker() {
    final EmbeddedKafkaBroker broker = EmbeddedFlowableKafkaSupport.current();
    if (broker == null) {
      throw new IllegalStateException(
          "spring.kafka.bootstrap-servers was set but no embedded Kafka broker was started; "
              + "this indicates flowable-test-spring-boot-starter's own EnvironmentPostProcessor "
              + "did not run as expected.");
    }
    return broker;
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  KafkaTestBridge kafkaTestBridge(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    return new KafkaTestBridge(bootstrapServers);
  }
}
