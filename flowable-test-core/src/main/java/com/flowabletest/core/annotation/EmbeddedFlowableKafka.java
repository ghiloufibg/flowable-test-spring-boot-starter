package com.flowabletest.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures additional Kafka topics for the embedded broker that {@code
 * FlowableTestKafkaAutoConfiguration} starts whenever {@code EmbeddedKafkaBroker}
 * (spring-kafka-test) is on the classpath. Most topics never need this annotation: they are
 * discovered automatically by scanning the consumer's Flowable Kafka Event Registry {@code
 * *.channel} descriptors for {@code channelType: "kafka"} entries. Declare a topic here only when
 * it exists outside that registry, for example one a test publishes to directly without a declared
 * inbound channel.
 *
 * <p>Applied through a {@code ContextCustomizer} rather than the environment post-processor that
 * performs the Event Registry scan, since that post-processor has no visibility into per-class
 * annotations ({@code @MockExternalService} needs its own customizer for the same reason). Because
 * the embedded broker is a JVM-wide singleton started at most once per JVM, {@link #partitions()}
 * applies only to this annotation's own {@link #additionalTopics()} and cannot change the partition
 * count of a topic that the Event Registry scan, or an earlier test class in the same JVM, already
 * created.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EmbeddedFlowableKafka {

  /** Partition count for {@link #additionalTopics()} specifically -- see class Javadoc. */
  int partitions() default 1;

  /** Topics to start in addition to the ones auto-discovered from Event Registry channel files. */
  String[] additionalTopics() default {};
}
