package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.kafka.KafkaTestBridge;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Proves the whole Kafka capability end to end: the {@code
 * EnvironmentPostProcessor} discovers topics from the real {@code *.channel} fixtures under {@code
 * src/test/resources/channel-fixtures}, starts a broker, injects {@code
 * spring.kafka.bootstrap-servers} before context refresh, and {@link
 * FlowableTestKafkaAutoConfiguration} exposes both the broker and a working {@link KafkaTestBridge}
 * as beans -- with no {@code @EmbeddedKafka(topics = ...)} anywhere in this test.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class FlowableTestKafkaAutoConfigurationTest {

  @Autowired Environment environment;
  @Autowired EmbeddedKafkaBroker embeddedKafkaBroker;
  @Autowired KafkaTestBridge kafkaTestBridge;

  @Test
  void bootstrapServersPropertyIsSetBeforeContextRefresh() {
    assertThat(environment.getProperty("spring.kafka.bootstrap-servers")).isNotBlank();
  }

  @Test
  void discoveredTopicFromFixtureChannelIsUsable() {
    assertThat(embeddedKafkaBroker.getTopics()).contains("order-events");
  }

  @Test
  void kafkaTestBridgeCanSendAndAwaitAMessage() {
    final String marker = UUID.randomUUID().toString();
    kafkaTestBridge.send("order-events", "key-1", "{\"marker\":\"" + marker + "\"}");

    final String received =
        kafkaTestBridge.awaitMessage(
            "order-events", value -> value.contains(marker), Duration.ofSeconds(15));

    assertThat(received).contains(marker);
  }
}
