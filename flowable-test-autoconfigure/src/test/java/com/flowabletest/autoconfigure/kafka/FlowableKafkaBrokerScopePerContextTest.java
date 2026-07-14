package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Proves {@code flowable.test.kafka.broker-scope=per-context}: each Spring context gets its own
 * freshly-started {@link EmbeddedKafkaBroker}, never the JVM-wide singleton.
 *
 * <p>Drives {@link SpringApplicationBuilder} directly rather than {@code @FlowableProcessTest} or
 * {@code ApplicationContextRunner} (the pattern {@code EmbeddedPostgresInstanceScopeTest} uses for
 * the analogous Postgres property), so one test method can deterministically boot two independent
 * contexts back to back. {@code ApplicationContextRunner} bypasses Spring Boot's {@code
 * EnvironmentPostProcessor} SPI entirely -- the stage where {@code broker-scope} is actually read
 * (see {@link FlowableTestKafkaEnvironmentPostProcessor}) -- so it can't be used here the way it is
 * for Postgres's plain {@code @Bean}-based wiring.
 */
class FlowableKafkaBrokerScopePerContextTest {

  @Test
  void eachContextGetsItsOwnBroker() {
    try (ConfigurableApplicationContext first = startContext();
        ConfigurableApplicationContext second = startContext()) {
      final String firstBrokers = first.getBean(EmbeddedKafkaBroker.class).getBrokersAsString();
      final String secondBrokers = second.getBean(EmbeddedKafkaBroker.class).getBrokersAsString();

      assertThat(firstBrokers).isNotBlank();
      assertThat(secondBrokers).isNotBlank();
      assertThat(secondBrokers).isNotEqualTo(firstBrokers);
    }
  }

  /**
   * The mirror image of {@code FlowableTestKafkaAutoConfigurationTest}'s shared-scope assertion: a
   * per-context broker is not shared with any other context and has no JVM shutdown hook of its own
   * (see {@link FlowableTestKafkaAutoConfiguration#embeddedKafkaBrokerPerContext()}), so it must
   * <em>not</em> be wrapped by {@link SharedEmbeddedKafkaBrokerGuard} -- Spring's own {@code
   * destroy()} call when this context closes is this broker's only, and correct, cleanup path.
   */
  @Test
  void perContextBrokerIsNotGuarded() {
    try (ConfigurableApplicationContext context = startContext()) {
      final EmbeddedKafkaBroker broker = context.getBean(EmbeddedKafkaBroker.class);

      assertThat(Proxy.isProxyClass(broker.getClass()))
          .as(
              "per-context broker must be the real instance, not wrapped by "
                  + "SharedEmbeddedKafkaBrokerGuard, so Spring's own destroy() call on context close "
                  + "actually tears it down")
          .isFalse();
    }
  }

  private static ConfigurableApplicationContext startContext() {
    return new SpringApplicationBuilder(SampleFlowableApplication.class)
        .web(WebApplicationType.NONE)
        .properties("flowable.test.kafka.broker-scope=per-context")
        .run();
  }
}
