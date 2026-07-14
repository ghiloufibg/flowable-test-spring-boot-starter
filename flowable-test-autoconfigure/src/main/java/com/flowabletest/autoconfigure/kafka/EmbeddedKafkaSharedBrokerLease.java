package com.flowabletest.autoconfigure.kafka;

import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Per-context handle on the JVM-wide shared embedded Kafka broker, obtained from {@link
 * EmbeddedFlowableKafkaSupport#acquireLease()}. {@link #release()} is registered as this bean's
 * Spring {@code destroyMethod} in {@link FlowableTestKafkaAutoConfiguration}, so the lease is
 * released when the owning context closes; see {@link EmbeddedFlowableKafkaSupport} for what this
 * protects against.
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
