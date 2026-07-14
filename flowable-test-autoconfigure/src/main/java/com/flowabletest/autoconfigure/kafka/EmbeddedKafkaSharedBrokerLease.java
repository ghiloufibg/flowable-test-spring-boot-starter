package com.flowabletest.autoconfigure.kafka;

import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * A per-context handle on the JVM-wide shared embedded Kafka broker, returned by {@link
 * EmbeddedFlowableKafkaSupport#acquireLease()}. {@link #release()} is registered as this bean's
 * Spring {@code destroyMethod} in {@link FlowableTestKafkaAutoConfiguration}; see {@link
 * EmbeddedFlowableKafkaSupport}'s Javadoc for what this guards against.
 */
final class EmbeddedKafkaSharedBrokerLease {

  private final EmbeddedKafkaBroker broker;

  EmbeddedKafkaSharedBrokerLease(final EmbeddedKafkaBroker broker) {
    this.broker = broker;
  }

  EmbeddedKafkaBroker broker() {
    return broker;
  }

  void release() {
    EmbeddedFlowableKafkaSupport.releaseLease();
  }
}
