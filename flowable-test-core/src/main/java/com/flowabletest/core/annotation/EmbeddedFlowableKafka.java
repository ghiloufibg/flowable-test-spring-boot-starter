package com.flowabletest.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional fine-tuning for the embedded Kafka broker that {@code
 * FlowableTestKafkaAutoConfiguration} starts automatically whenever {@code EmbeddedKafkaBroker}
 * (spring-kafka-test) is on the classpath. The topic list itself is <b>not</b> declared here -- it
 * is derived automatically by scanning the consumer's Flowable Kafka Event Registry {@code
 * *.channel} descriptors for {@code channelType: "kafka"} entries (design doc section 4.2). This
 * annotation only exists for topics that exist outside that registry (e.g. a topic a test publishes
 * to directly without a declared inbound channel).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EmbeddedFlowableKafka {

  int partitions() default 1;

  /** Topics to start in addition to the ones auto-discovered from Event Registry channel files. */
  String[] additionalTopics() default {};
}
