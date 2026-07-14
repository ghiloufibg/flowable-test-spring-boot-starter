package com.flowabletest.autoconfigure.kafka;

import java.util.function.Consumer;
import org.flowable.eventregistry.spring.kafka.KafkaChannelDefinitionProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.util.ClassUtils;

/**
 * Starts/stops Flowable's inbound Kafka Event Registry consumer container(s) at Spring test class
 * boundaries, active only when {@code flowable.test.kafka.broker-scope=shared} (the default; see
 * {@link FlowableKafkaBrokerScopeCondition}).
 *
 * <p>The shared broker is a JVM-wide singleton; Spring's {@code TestContextCache} keeps every
 * distinct {@code ApplicationContext} it builds resident for the rest of the JVM's life, so without
 * this listener two cached contexts' engines would both register as competing consumers, in the
 * same hardcoded consumer group, against the same broker. Stopping the previous class's containers
 * before the next class's run begins prevents that; {@link #beforeTestClass} idempotently restarts
 * them if the very next class reuses this same cached context.
 *
 * <p>Registered via {@code META-INF/spring.factories} under {@code
 * org.springframework.test.context.TestExecutionListener}, which means it is unconditionally
 * instantiated for <b>every</b> test using the Spring TestContext framework, including consumers of
 * this starter with no Kafka on their classpath at all. {@link #isSharedModeWithKafkaOnClasspath}
 * guards {@link #beforeTestClass}/{@link #afterTestClass} against that -- deliberately kept free of
 * any Kafka/Flowable-Kafka type reference itself, and checked <b>before</b> either override creates
 * a lambda referencing {@link MessageListenerContainer}, since lambda creation ({@code
 * invokedynamic}) resolves its target type the first time that bytecode executes regardless of
 * which branch calls it. Same classpath-guard discipline already used by {@link
 * FlowableTestKafkaEnvironmentPostProcessor}.
 *
 * <p>Only acts on containers registered by Flowable itself (matched via {@link
 * KafkaChannelDefinitionProcessor#CHANNEL_ID_PREFIX}), never on the {@link
 * KafkaListenerEndpointRegistry} as a whole -- that registry is a single bean shared with any
 * unrelated {@code @KafkaListener}s the consumer app might separately declare.
 */
public final class FlowableKafkaConsumerLifecycleTestExecutionListener
    implements TestExecutionListener {

  @Override
  public void beforeTestClass(final TestContext testContext) {
    if (isSharedModeWithKafkaOnClasspath(testContext)) {
      startFlowableManagedContainers(testContext);
    }
  }

  @Override
  public void afterTestClass(final TestContext testContext) {
    if (isSharedModeWithKafkaOnClasspath(testContext)) {
      stopFlowableManagedContainers(testContext);
    }
  }

  private boolean isSharedModeWithKafkaOnClasspath(final TestContext testContext) {
    if (!classPresent("org.springframework.kafka.config.KafkaListenerEndpointRegistry")
        || !classPresent(
            "org.flowable.eventregistry.spring.kafka.KafkaChannelDefinitionProcessor")) {
      return false;
    }
    return FlowableKafkaBrokerScopeCondition.isShared(
        testContext.getApplicationContext().getEnvironment());
  }

  private void startFlowableManagedContainers(final TestContext testContext) {
    forEachFlowableManagedContainer(
        testContext,
        container -> {
          if (!container.isRunning()) {
            container.start();
          }
        });
  }

  private void stopFlowableManagedContainers(final TestContext testContext) {
    forEachFlowableManagedContainer(testContext, MessageListenerContainer::stop);
  }

  private void forEachFlowableManagedContainer(
      final TestContext testContext, final Consumer<MessageListenerContainer> action) {
    final ApplicationContext context = testContext.getApplicationContext();
    final String[] registryNames =
        context.getBeanNamesForType(KafkaListenerEndpointRegistry.class, false, false);
    if (registryNames.length == 0) {
      return;
    }
    final KafkaListenerEndpointRegistry registry =
        context.getBean(registryNames[0], KafkaListenerEndpointRegistry.class);
    registry
        .getListenerContainersMatching(
            id -> id.startsWith(KafkaChannelDefinitionProcessor.CHANNEL_ID_PREFIX))
        .forEach(action);
  }

  private static boolean classPresent(final String className) {
    return ClassUtils.isPresent(
        className, FlowableKafkaConsumerLifecycleTestExecutionListener.class.getClassLoader());
  }
}
