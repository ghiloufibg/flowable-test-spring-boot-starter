package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.kafka.KafkaTestBridge;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Proves the real-world scenario {@link FlowableTestKafkaBridgeCondition} exists for: {@code
 * flowable.test.kafka.enabled=false} plus a broker the starter never started or knows about --
 * standing in for a consumer's own Docker/Testcontainers Kafka. Starts a broker directly (the same
 * {@code EmbeddedKafkaKraftBroker} idiom {@link EmbeddedFlowableKafkaSupport} itself uses), point
 * {@code spring.kafka.bootstrap-servers} at it, and expect {@code KafkaTestBridge} to be usable
 * with no manual {@code @Bean} declaration -- while the starter's own {@code EmbeddedKafkaBroker}
 * bean must stay absent, since it never provisioned this one.
 */
class FlowableTestKafkaBridgeAgainstExternalBrokerTest {

  private EmbeddedKafkaBroker externalBroker;

  @BeforeEach
  void startExternalBroker() throws Exception {
    externalBroker = new EmbeddedKafkaKraftBroker(1, 1, "order-events");
    externalBroker.afterPropertiesSet();
  }

  @AfterEach
  void stopExternalBroker() {
    externalBroker.destroy();
  }

  @Test
  void kafkaTestBridgeAutoRegistersAgainstABrokerTheStarterNeverProvisioned() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SampleFlowableApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "flowable.test.kafka.enabled=false",
                "spring.kafka.bootstrap-servers=" + externalBroker.getBrokersAsString())
            .run()) {

      assertThat(context.getBeansOfType(EmbeddedKafkaBroker.class))
          .as("the starter must not claim ownership of a broker it never started")
          .isEmpty();

      final KafkaTestBridge kafkaTestBridge = context.getBean(KafkaTestBridge.class);
      final String marker = UUID.randomUUID().toString();
      kafkaTestBridge.send("order-events", "key-1", "{\"marker\":\"" + marker + "\"}");

      final String received =
          kafkaTestBridge.awaitMessage(
              "order-events", value -> value.contains(marker), Duration.ofSeconds(15));

      assertThat(received).contains(marker);
    }
  }

  @Test
  void kafkaTestBridgeIsAbsentWhenNoKafkaEventRegistryChannelsAreDeclared() {
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(SampleFlowableApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "flowable.test.kafka.enabled=false",
                "flowable.test.kafka.channel-location=classpath*:channel-fixtures/non-kafka-channel.channel",
                "spring.kafka.bootstrap-servers=" + externalBroker.getBrokersAsString())
            .run()) {

      assertThat(context.getBeanNamesForType(KafkaTestBridge.class))
          .as(
              "no channels declared for this location -- must not wire a bridge to an unrelated broker")
          .isEmpty();
    }
  }
}
