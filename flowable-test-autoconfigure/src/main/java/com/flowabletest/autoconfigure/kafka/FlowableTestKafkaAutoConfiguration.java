package com.flowabletest.autoconfigure.kafka;

import com.flowabletest.core.kafka.KafkaTestBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
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
   * {@code destroyMethod = ""} disables Spring's destroy-method inference/named-method lookup, but
   * {@code EmbeddedKafkaBroker} extends Spring's own {@code DisposableBean}, and {@code
   * DisposableBeanAdapter} invokes that interface callback unconditionally regardless of {@code
   * destroyMethod} -- so {@code destroyMethod = ""} alone does not stop *this* context from calling
   * {@code destroy()} on the broker when it closes. The broker is a JVM-wide singleton shared
   * across every context that discovers Kafka Event Registry channels ({@link
   * EmbeddedFlowableKafkaSupport}, started at most once per JVM) and already has its own JVM
   * shutdown hook as its one true owner; {@link SharedEmbeddedKafkaBrokerGuard} wraps the returned
   * broker so that interface-driven per-context {@code destroy()} calls are no-ops, leaving the
   * shutdown hook's call the only one that actually tears it down. See that class's Javadoc for the
   * failure this prevents.
   */
  @Bean(destroyMethod = "")
  @ConditionalOnMissingBean
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  @Conditional(FlowableKafkaBrokerScopeCondition.Shared.class)
  EmbeddedKafkaBroker embeddedKafkaBroker() {
    final EmbeddedKafkaBroker broker = EmbeddedFlowableKafkaSupport.current();
    if (broker == null) {
      throw new IllegalStateException(
          "spring.kafka.bootstrap-servers was set but no embedded Kafka broker was started; "
              + "this indicates flowable-test-spring-boot-starter's own EnvironmentPostProcessor "
              + "did not run as expected.");
    }
    return SharedEmbeddedKafkaBrokerGuard.suppressDestroy(broker);
  }

  /**
   * {@code destroyMethod = "destroy"} here is the mirror image of {@link #embeddedKafkaBroker()}'s
   * disabled destroy method: a {@code per-context} broker ({@link
   * EmbeddedFlowableKafkaSupport#startFresh}) has no JVM shutdown hook of its own, so letting
   * Spring call {@code destroy()} when *this* context closes is the only cleanup path it gets, not
   * a double-destroy risk.
   */
  @Bean(destroyMethod = "destroy")
  @ConditionalOnMissingBean
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  @Conditional(FlowableKafkaBrokerScopeCondition.PerContext.class)
  EmbeddedKafkaBroker embeddedKafkaBrokerPerContext() {
    final EmbeddedKafkaBroker broker = EmbeddedFlowableKafkaSupport.currentPerContext();
    if (broker == null) {
      throw new IllegalStateException(
          "spring.kafka.bootstrap-servers was set but no per-context embedded Kafka broker was "
              + "started; this indicates flowable-test-spring-boot-starter's own "
              + "EnvironmentPostProcessor did not run as expected.");
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
