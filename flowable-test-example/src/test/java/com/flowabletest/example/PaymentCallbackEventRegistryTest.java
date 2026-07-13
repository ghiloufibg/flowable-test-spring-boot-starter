package com.flowabletest.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.MockExternalService;
import com.flowabletest.core.harness.ProcessTestHarness;
import com.flowabletest.core.kafka.KafkaTestBridge;
import com.flowabletest.example.domain.Order;
import com.flowabletest.example.domain.OrderStatus;
import com.flowabletest.example.repository.OrderRepository;
import com.flowabletest.example.service.OrderProcessService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the inbound Kafka Event Registry side: a message published to the auto-discovered
 * "payment-callbacks" topic is correlated by Flowable itself (via the {@code orderId} correlation
 * parameter) to the correct running process instance, without any hand-rolled
 * {@code @KafkaListener}. The order is parked at Manager Approval (fraud check redirected to the
 * decline stub) purely to keep the process instance's event-subprocess subscription alive long
 * enough to receive the callback.
 */
@FlowableProcessTest
@MockExternalService(
    name = "fraud-check-service",
    stubs = "classpath:httpmocks/fraud-check-service-manual-review")
class PaymentCallbackEventRegistryTest {

  @Autowired OrderProcessService orderProcessService;
  @Autowired ProcessTestHarness harness;
  @Autowired KafkaTestBridge kafkaTestBridge;
  @Autowired OrderRepository orderRepository;

  @Test
  void paymentCallbackMessageCorrelatesToRunningInstanceAndUpdatesOrderStatus() {
    final Order order = orderProcessService.startOrderProcess("CUST-004", new BigDecimal("300.00"));
    harness.awaitTaskForCandidateGroup(
        order.getProcessInstanceId(), "managers", Duration.ofSeconds(15));

    final String callback =
        """
        {"orderId":"%s","status":"SUCCESS","confirmationId":"PAY-%s"}
        """
            .formatted(order.getId(), order.getId())
            .strip();
    kafkaTestBridge.send("payment-callbacks", order.getId(), callback);

    final Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
    Order updated;
    do {
      updated = orderRepository.findById(order.getId()).orElseThrow();
    } while (updated.getStatus() != OrderStatus.PAID && Instant.now().isBefore(deadline));

    assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(updated.getPaymentConfirmationId()).isEqualTo("PAY-" + order.getId());

    // Clean up the still-parked instance.
    harness.completeSingleTask(order.getProcessInstanceId(), "managers", Map.of("approved", false));
  }
}
