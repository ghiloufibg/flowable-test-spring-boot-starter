/**
 * {@link com.flowabletest.core.kafka.KafkaTestBridge} -- the generic replacement for hand-rolled
 * {@code KafkaProducer}/{@code KafkaConsumer} setup in a Flowable + Kafka Event Registry test
 * class. Operates purely on topic/key/value strings, with no payload schema knowledge; consumption
 * blocks on Kafka's own native long-poll ({@code KafkaConsumer.poll(Duration)}) rather than a
 * fixed-interval sleep loop. Registered as a bean by {@code FlowableTestKafkaAutoConfiguration},
 * pointed at whichever embedded broker -- shared JVM-wide singleton or per-context -- is active for
 * the test.
 */
package com.flowabletest.core.kafka;
