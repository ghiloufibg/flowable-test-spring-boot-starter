package com.flowabletest.autoconfigure.kafka;

import com.flowabletest.core.kafka.KafkaTestBridge;
import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Auto-configuration for embedded Kafka test support. Activates when {@code EmbeddedKafkaBroker}
 * and Flowable's {@code RuntimeService} are both on the classpath and a {@code ProcessEngine} bean
 * exists. Exposes the broker started by {@link FlowableTestKafkaEnvironmentPostProcessor} as an
 * ordinary bean (for consumers who want {@code @Autowired EmbeddedKafkaBroker}) and registers a
 * {@link KafkaTestBridge} pointed at it. The two broker beans are gated by {@link
 * FlowableTestKafkaProvisionedCondition}, matched only when that post-processor actually
 * provisioned a broker for this context -- deliberately not keyed off whether {@code
 * spring.kafka.bootstrap-servers} happens to be set, since a consumer may set that property
 * independently (a real broker, Testcontainers) with no Kafka Event Registry channel descriptors on
 * the classpath at all. The {@code KafkaTestBridge} bean uses the broader {@link
 * FlowableTestKafkaBridgeCondition} instead, so it is still registered when {@code
 * flowable.test.kafka.enabled=false} and the consumer points {@code spring.kafka.bootstrap-servers}
 * at a real broker themselves -- provided this project actually declares Kafka Event Registry
 * channels, the same signal the post-processor itself uses to decide whether to start an embedded
 * broker at all.
 */
@AutoConfiguration(
    afterName = {
      "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
      "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration"
    })
@ConditionalOnClass(value = EmbeddedKafkaBroker.class, name = "org.flowable.engine.RuntimeService")
@ConditionalOnBean(ProcessEngine.class)
public class FlowableTestKafkaAutoConfiguration {

  /**
   * Exposes the JVM-wide shared broker as a bean with {@code destroyMethod = ""}. That attribute
   * alone does not stop Spring from calling {@code destroy()} on this bean, because {@code
   * EmbeddedKafkaBroker} implements Spring's own {@code DisposableBean} and {@code
   * DisposableBeanAdapter} invokes that interface callback unconditionally. {@link
   * SharedEmbeddedKafkaBrokerGuard} wraps the broker so that per-context {@code destroy()} calls
   * become no-ops, leaving only the shutdown hook registered in {@link
   * EmbeddedFlowableKafkaSupport} -- the broker's one true owner -- to actually tear it down. See
   * that guard's Javadoc for the failure this prevents.
   */
  @Bean(destroyMethod = "")
  @ConditionalOnMissingBean
  @Conditional({
    FlowableTestKafkaProvisionedCondition.class,
    FlowableKafkaBrokerScopeCondition.Shared.class
  })
  EmbeddedKafkaBroker embeddedKafkaBroker() {
    final EmbeddedKafkaBroker broker = EmbeddedFlowableKafkaSupport.current();
    if (broker == null) {
      throw new IllegalStateException(
          "FlowableTestKafkaProvisionedCondition matched but no shared embedded Kafka broker was "
              + "started; this indicates flowable-test-spring-boot-starter's own "
              + "EnvironmentPostProcessor did not run as expected.");
    }
    return SharedEmbeddedKafkaBrokerGuard.suppressDestroy(broker);
  }

  /**
   * Exposes a {@code per-context} broker with {@code destroyMethod = "destroy"}: unlike the shared
   * broker, a per-context broker ({@link EmbeddedFlowableKafkaSupport#startFresh}) has no JVM
   * shutdown hook of its own, so letting Spring call {@code destroy()} when this context closes is
   * its only cleanup path, not a double-destroy risk.
   */
  @Bean(destroyMethod = "destroy")
  @ConditionalOnMissingBean
  @Conditional({
    FlowableTestKafkaProvisionedCondition.class,
    FlowableKafkaBrokerScopeCondition.PerContext.class
  })
  EmbeddedKafkaBroker embeddedKafkaBrokerPerContext() {
    final EmbeddedKafkaBroker broker = EmbeddedFlowableKafkaSupport.currentPerContext();
    if (broker == null) {
      throw new IllegalStateException(
          "FlowableTestKafkaProvisionedCondition matched but no per-context embedded Kafka broker "
              + "was started; this indicates flowable-test-spring-boot-starter's own "
              + "EnvironmentPostProcessor did not run as expected.");
    }
    return broker;
  }

  /**
   * Registers a {@link KafkaTestBridge} wired to whatever {@code spring.kafka.bootstrap-servers}
   * resolves to -- the starter's own embedded broker, or a real broker the consumer supplied
   * themselves. See {@link FlowableTestKafkaBridgeCondition} for when each case activates this
   * bean.
   */
  @Bean
  @ConditionalOnMissingBean
  @Conditional(FlowableTestKafkaBridgeCondition.class)
  KafkaTestBridge kafkaTestBridge(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    return new KafkaTestBridge(bootstrapServers);
  }
}
