package com.flowabletest.autoconfigure.kafka;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches for the {@code KafkaTestBridge} bean specifically -- broader than {@link
 * FlowableTestKafkaProvisionedCondition}, which every other bean in {@link
 * FlowableTestKafkaAutoConfiguration} still uses. Matches when either that stricter condition
 * matches (the starter provisioned its own embedded broker), or this project declares real Kafka
 * Event Registry channels (proving the project genuinely uses the registry, not merely that some
 * unrelated {@code spring.kafka.bootstrap-servers} happens to be set) and {@code
 * spring.kafka.bootstrap-servers} already resolves to something -- the case where {@code
 * flowable.test.kafka.enabled=false} and the consumer points at a real broker (Docker, CI,
 * Testcontainers) themselves. Reuses the exact same channel-discovery signal {@link
 * FlowableTestKafkaEnvironmentPostProcessor} uses to decide whether to start an embedded broker at
 * all, so a project with no Kafka Event Registry channels still never gets a {@code
 * KafkaTestBridge} bean pointed at someone else's unrelated broker.
 */
final class FlowableTestKafkaBridgeCondition implements Condition {

  private final FlowableTestKafkaProvisionedCondition provisionedCondition =
      new FlowableTestKafkaProvisionedCondition();

  @Override
  public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
    return provisionedCondition.matches(context, metadata)
        || channelsDeclaredAndBootstrapServersConfigured(context.getEnvironment());
  }

  private boolean channelsDeclaredAndBootstrapServersConfigured(final Environment environment) {
    if (!StringUtils.hasText(environment.getProperty("spring.kafka.bootstrap-servers"))) {
      return false;
    }

    final EventRegistryChannelScanner scanner =
        new EventRegistryChannelScanner(new PathMatchingResourcePatternResolver());
    final String channelLocation =
        environment.getProperty("flowable.test.kafka.channel-location", "classpath*:**/*.channel");
    return !scanner.discoverKafkaTopics(channelLocation).isEmpty();
  }
}
