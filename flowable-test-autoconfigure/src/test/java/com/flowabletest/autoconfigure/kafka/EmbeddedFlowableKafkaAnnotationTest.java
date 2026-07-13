package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.EmbeddedFlowableKafka;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.kafka.KafkaTestBridge;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Proves {@link EmbeddedFlowableKafka#additionalTopics()} actually adds a usable topic to the
 * embedded broker, on top of whatever the Event Registry channel scan already discovered -- the
 * annotation's own escape-hatch use case (design doc section 4.2), applied via {@link
 * EmbeddedFlowableKafkaContextCustomizer} rather than the environment post-processor, which has no
 * visibility into this test class's annotations.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
@EmbeddedFlowableKafka(additionalTopics = "annotation-declared-topic")
class EmbeddedFlowableKafkaAnnotationTest {

  @Autowired EmbeddedKafkaBroker embeddedKafkaBroker;
  @Autowired KafkaTestBridge kafkaTestBridge;

  @Test
  void additionalTopicFromTheAnnotationIsUsable() {
    assertThat(embeddedKafkaBroker.getTopics()).contains("annotation-declared-topic");

    final String marker = UUID.randomUUID().toString();
    kafkaTestBridge.send("annotation-declared-topic", "key-1", marker);

    final String received =
        kafkaTestBridge.awaitMessage(
            "annotation-declared-topic", value -> value.contains(marker), Duration.ofSeconds(15));

    assertThat(received).contains(marker);
  }
}
