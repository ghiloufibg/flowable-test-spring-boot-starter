/**
 * Test-class annotations: {@link com.flowabletest.core.annotation.FlowableProcessTest}, the
 * one-annotation {@code @SpringBootTest} replacement every consumer test starts from, and two
 * narrow escape hatches for the rare case its auto-discovered defaults aren't enough -- {@link
 * com.flowabletest.core.annotation.MockExternalService} (redirect one test class to alternate HTTP
 * stubs) and {@link com.flowabletest.core.annotation.EmbeddedFlowableKafka} (declare topics that
 * exist outside the Kafka Event Registry). None of these annotations carry auto-configuration
 * themselves -- that lives in {@code flowable-test-autoconfigure} and activates automatically based
 * on what's on the consumer's classpath.
 */
package com.flowabletest.core.annotation;
