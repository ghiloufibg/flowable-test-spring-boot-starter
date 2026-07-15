package com.flowabletest.autoconfigure.kafka;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.util.ClassUtils;

/**
 * Starts the embedded Kafka broker and injects {@code spring.kafka.bootstrap-servers} into the
 * {@link ConfigurableEnvironment} before the {@code ApplicationContext} refreshes. This has to
 * happen at the {@code EnvironmentPostProcessor} stage, not in a regular {@code @Bean}, because
 * Spring Kafka's auto-configured producer/consumer factories and Flowable's Kafka Event Registry
 * channels read {@code spring.kafka.bootstrap-servers} while they themselves are being created, and
 * same-context {@code @Bean} ordering cannot guarantee this class runs first.
 *
 * <p>Ordered to run last ({@link Ordered#LOWEST_PRECEDENCE}) among environment post-processors, so
 * that {@code ConfigDataEnvironmentPostProcessor} has already loaded {@code application.yml}/{@code
 * application-test.yml} and any consumer overrides of {@code flowable.test.kafka.*} are visible
 * when this class reads them.
 *
 * <p>{@code flowable.test.kafka.broker-scope} controls which broker gets started: {@code shared}
 * (the default) reuses the JVM-wide singleton, {@code per-context} starts a brand-new broker for
 * this context alone. See {@link FlowableKafkaBrokerScopeCondition}.
 */
public final class FlowableTestKafkaEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  static final String PROPERTY_SOURCE_NAME = "flowableTestEmbeddedKafka";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
      return;
    }
    if (!classPresent("org.springframework.kafka.test.EmbeddedKafkaBroker")
        || !classPresent("org.flowable.engine.RuntimeService")) {
      return;
    }
    if (!environment.getProperty("flowable.test.kafka.enabled", Boolean.class, true)) {
      return;
    }

    final EventRegistryChannelScanner scanner =
        new EventRegistryChannelScanner(new PathMatchingResourcePatternResolver());
    final String channelLocation =
        environment.getProperty("flowable.test.kafka.channel-location", "classpath*:**/*.channel");
    final Set<String> topics = scanner.discoverKafkaTopics(channelLocation);
    if (topics.isEmpty()) {
      return;
    }

    final int partitions =
        environment.getProperty("flowable.test.kafka.partitions", Integer.class, 1);
    final EmbeddedKafkaBroker broker =
        FlowableKafkaBrokerScopeCondition.isShared(environment)
            ? EmbeddedFlowableKafkaSupport.startIfNeeded(topics, partitions)
            : EmbeddedFlowableKafkaSupport.startFresh(topics, partitions);

    final Map<String, Object> properties = new HashMap<>();
    properties.put("spring.kafka.bootstrap-servers", broker.getBrokersAsString());
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  private static boolean classPresent(String className) {
    return ClassUtils.isPresent(
        className, FlowableTestKafkaEnvironmentPostProcessor.class.getClassLoader());
  }
}
