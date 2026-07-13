package com.flowabletest.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import com.flowabletest.core.kafka.KafkaTestBridge;
import com.flowabletest.example.domain.Order;
import com.flowabletest.example.service.OrderProcessService;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The default WireMock stub (src/test/resources/httpmocks/fraud-check-service) approves every fraud
 * check, so this order never stops at Manager Approval. Proves, end to end: the send-event task
 * publishes onto the Kafka Event Registry's auto-discovered "order-events" topic, and the
 * WireMock-mocked fraud-check delegate call drives the gateway to the auto-approved path.
 */
@FlowableProcessTest
class OrderAutoApprovedFlowTest {

  @Autowired OrderProcessService orderProcessService;
  @Autowired ProcessTestHarness harness;
  @Autowired KafkaTestBridge kafkaTestBridge;

  @Test
  void approvedFraudCheckSkipsManagerApprovalAndPublishesOrderCreatedEvent() {
    final Order order = orderProcessService.startOrderProcess("CUST-001", new BigDecimal("49.99"));

    harness.assertThat(order.getProcessInstanceId()).hasEndedAt("endEventAccepted");

    final String message =
        kafkaTestBridge.awaitMessage(
            "order-events", value -> value.contains(order.getId()), Duration.ofSeconds(15));
    assertThat(message).contains("\"customerId\":\"CUST-001\"");
  }
}
