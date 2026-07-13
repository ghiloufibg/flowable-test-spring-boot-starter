package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Proves {@code flowable.test.kafka.broker-scope=per-context}: each Spring context gets its own
 * freshly-started {@link EmbeddedKafkaBroker}, never the JVM-wide singleton (design doc: {@code
 * claudedocs/kafka-shared-broker-context-isolation-design.md}).
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

  private static ConfigurableApplicationContext startContext() {
    return new SpringApplicationBuilder(SampleFlowableApplication.class)
        .web(WebApplicationType.NONE)
        .properties("flowable.test.kafka.broker-scope=per-context")
        .run();
  }
}
