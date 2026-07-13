package com.flowabletest.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.MockExternalService;
import com.flowabletest.core.harness.ProcessTestHarness;
import com.flowabletest.example.domain.Order;
import com.flowabletest.example.service.OrderProcessService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Redirects the fraud-check service to the "decline" stub folder for this test class only (design
 * doc's {@code @MockExternalService} escape hatch), so every order here parks at Manager Approval
 * instead of the {@link OrderAutoApprovedFlowTest} default.
 */
@FlowableProcessTest
@MockExternalService(
    name = "fraud-check-service",
    stubs = "classpath:httpmocks/fraud-check-service-manual-review")
class OrderManualReviewFlowTest {

  @Autowired OrderProcessService orderProcessService;
  @Autowired ProcessTestHarness harness;

  @Test
  void managerApprovesOrder_completesAtEndEventAccepted() {
    final Order order = orderProcessService.startOrderProcess("CUST-002", new BigDecimal("120.00"));

    final Task approvalTask =
        harness.awaitTaskForCandidateGroup(
            order.getProcessInstanceId(), "managers", Duration.ofSeconds(15));
    assertThat(approvalTask.getName()).isEqualTo("Manager Approval");

    harness.completeSingleTask(order.getProcessInstanceId(), "managers", Map.of("approved", true));

    harness.assertThat(order.getProcessInstanceId()).hasEndedAt("endEventAccepted");
  }

  @Test
  void managerRejectsOrder_completesAtEndEventRejected() {
    final Order order = orderProcessService.startOrderProcess("CUST-003", new BigDecimal("200.00"));

    harness.awaitTaskForCandidateGroup(
        order.getProcessInstanceId(), "managers", Duration.ofSeconds(15));
    harness.completeSingleTask(order.getProcessInstanceId(), "managers", Map.of("approved", false));

    harness.assertThat(order.getProcessInstanceId()).hasEndedAt("endEventRejected");
  }
}
