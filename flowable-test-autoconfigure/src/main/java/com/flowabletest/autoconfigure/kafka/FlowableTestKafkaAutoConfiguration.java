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
 * Auto-configuration for embedded Kafka test support. Activates when {@code EmbeddedKafkaBroker}
 * and Flowable's {@code RuntimeService} are both on the classpath. Exposes the broker started by
 * {@link FlowableTestKafkaEnvironmentPostProcessor} as an ordinary bean (for consumers who want
 * {@code @Autowired EmbeddedKafkaBroker}) and registers a {@link KafkaTestBridge} pointed at it.
 * Every bean here is additionally conditional on {@code spring.kafka.bootstrap-servers} actually
 * having been set by the post-processor; if no Kafka Event Registry channel descriptors were found
 * on the classpath, none of them activate.
 */
@AutoConfiguration
@ConditionalOnClass(value = EmbeddedKafkaBroker.class, name = "org.flowable.engine.RuntimeService")
public class FlowableTestKafkaAutoConfiguration {

  /**
   * Acquires this context's lease on the JVM-wide shared broker via {@code destroyMethod =
   * "release"}, so {@link EmbeddedFlowableKafkaSupport}'s shutdown hook knows to wait for this
   * context before destroying it. Guarded the same way as {@link
   * #embeddedKafkaBroker(EmbeddedKafkaSharedBrokerLease)}, so a lease is never acquired when a
   * consumer-supplied {@code EmbeddedKafkaBroker} bean means neither bean is actually needed.
   */
  @Bean(destroyMethod = "release")
  @ConditionalOnMissingBean(EmbeddedKafkaBroker.class)
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  @Conditional(FlowableKafkaBrokerScopeCondition.Shared.class)
  EmbeddedKafkaSharedBrokerLease embeddedKafkaSharedBrokerLease() {
    return EmbeddedFlowableKafkaSupport.acquireLease();
  }

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
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  @Conditional(FlowableKafkaBrokerScopeCondition.Shared.class)
  EmbeddedKafkaBroker embeddedKafkaBroker(EmbeddedKafkaSharedBrokerLease lease) {
    return SharedEmbeddedKafkaBrokerGuard.suppressDestroy(lease.broker());
  }

  /**
   * Exposes a {@code per-context} broker with {@code destroyMethod = "destroy"}: unlike the shared
   * broker, a per-context broker ({@link EmbeddedFlowableKafkaSupport#startFresh}) has no JVM
   * shutdown hook of its own, so letting Spring call {@code destroy()} when this context closes is
   * its only cleanup path, not a double-destroy risk.
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

  /** Registers a {@link KafkaTestBridge} wired to the embedded broker's bootstrap servers. */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty("spring.kafka.bootstrap-servers")
  KafkaTestBridge kafkaTestBridge(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    return new KafkaTestBridge(bootstrapServers);
  }
}
