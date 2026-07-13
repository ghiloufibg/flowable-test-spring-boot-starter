package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Pure unit test, no Spring context: proves the scanner correctly parses Flowable's real Event
 * Registry {@code *.channel} JSON shape (fixtures mirror the actual format from a live Flowable
 * project) -- both the outbound single-{@code "topic"} form and the inbound {@code "topics"} array
 * form -- while ignoring non-Kafka channels (design doc section 4.2).
 */
class EventRegistryChannelScannerTest {

  private final EventRegistryChannelScanner scanner =
      new EventRegistryChannelScanner(new PathMatchingResourcePatternResolver());

  @Test
  void discoversTopicsFromBothOutboundAndInboundChannelShapes_andIgnoresNonKafkaChannels() {
    final Set<String> topics = scanner.discoverKafkaTopics("classpath*:channel-fixtures/*.channel");

    assertThat(topics)
        .containsExactlyInAnyOrder("order-events", "payment-callbacks", "payment-callbacks-retry");
  }
}
