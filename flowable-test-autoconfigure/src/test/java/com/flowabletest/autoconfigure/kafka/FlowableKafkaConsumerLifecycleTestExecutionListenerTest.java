package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import org.flowable.eventregistry.spring.kafka.KafkaChannelDefinitionProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestContextManager;

/**
 * Proves {@link FlowableKafkaConsumerLifecycleTestExecutionListener}'s {@code shared}-mode start/
 * stop choreography (design doc: {@code
 * claudedocs/kafka-shared-broker-context-isolation-design.md}): the inbound {@code
 * paymentCallbackChannel} consumer container is running after {@code beforeTestClass}, stopped
 * after {@code afterTestClass}, and idempotently restarted by a subsequent {@code beforeTestClass}
 * -- exactly the sequence Spring's {@code TestContextCache} produces across two real test classes
 * sharing one cached context.
 *
 * <p>Drives {@link TestContextManager} directly (Spring Test's own API for this) against a fixture
 * class below, rather than relying on real multi-class JUnit execution order, which Surefire/JUnit
 * don't guarantee.
 */
class FlowableKafkaConsumerLifecycleTestExecutionListenerTest {

  private TestContextManager testContextManager;

  @AfterEach
  void leaveContainerRunningForAnyOtherTestSharingThisContext() throws Exception {
    if (testContextManager != null) {
      testContextManager.beforeTestClass();
    }
  }

  @Test
  void startsBeforeClassStopsAfterClassAndRestartsIdempotently() throws Exception {
    testContextManager = new TestContextManager(LifecycleTargetApp.class);

    testContextManager.beforeTestClass();
    final MessageListenerContainer container = paymentCallbackContainer();
    assertThat(container.isRunning()).isTrue();

    testContextManager.beforeTestClass();
    assertThat(container.isRunning()).isTrue();

    testContextManager.afterTestClass();
    assertThat(container.isRunning()).isFalse();

    testContextManager.beforeTestClass();
    assertThat(container.isRunning()).isTrue();
  }

  private MessageListenerContainer paymentCallbackContainer() {
    final ApplicationContext context = testContextManager.getTestContext().getApplicationContext();
    final KafkaListenerEndpointRegistry registry =
        context.getBean(KafkaListenerEndpointRegistry.class);
    final MessageListenerContainer container =
        registry.getListenerContainer(
            KafkaChannelDefinitionProcessor.CHANNEL_ID_PREFIX + "paymentCallbackChannel");
    assertThat(container).isNotNull();
    return container;
  }

  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      properties = "flowable.test.kafka.broker-scope=shared")
  static class LifecycleTargetApp {}
}
