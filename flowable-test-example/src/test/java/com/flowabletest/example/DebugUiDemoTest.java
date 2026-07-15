package com.flowabletest.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.MockExternalService;
import com.flowabletest.core.harness.ProcessTestHarness;
import com.flowabletest.debugui.DebugUiServer;
import com.flowabletest.example.domain.Order;
import com.flowabletest.example.service.OrderProcessService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the opt-in {@code flowable-test-debug-ui} module end to end against this app's real
 * {@code orderProcessing} process, the way an external consumer would: add the dependency, set
 * {@code flowable.test.debug-ui.enabled=true}, and query the three routes over real HTTP.
 *
 * <p>Redirects the fraud-check service to the manual-review stub (same as {@link
 * OrderManualReviewFlowTest}) so the queried instance is genuinely parked at a wait state with a
 * real pending task, rather than already-ended -- closer to what a developer would actually be
 * debugging. {@code order-processing.bpmn20.xml} deliberately has no {@code BPMNDI} section (it's
 * hand-authored to execute, not to round-trip through a modeler), so the diagram route's documented
 * {@code 422} response for that case is demonstrated here against a genuine, non-trivial process --
 * complementing {@code flowable-test-debug-ui}'s own equivalent test, which only proves it against
 * a minimal synthetic fixture.
 */
@FlowableProcessTest(properties = "flowable.test.debug-ui.enabled=true")
@MockExternalService(
    name = "fraud-check-service",
    stubs = "classpath:httpmocks/fraud-check-service-manual-review")
class DebugUiDemoTest {

  @Autowired OrderProcessService orderProcessService;
  @Autowired ProcessTestHarness harness;
  @Autowired DebugUiServer debugUiServer;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @Test
  void listAndDetailPagesShowTheWaitingOrder() throws Exception {
    final Order order = orderProcessService.startOrderProcess("CUST-004", new BigDecimal("75.00"));
    final Task approvalTask =
        harness.awaitTaskForCandidateGroup(
            order.getProcessInstanceId(), "managers", Duration.ofSeconds(15));
    assertThat(approvalTask.getName()).isEqualTo("Manager Approval");

    final HttpResponse<String> listPage = get("/");
    assertThat(listPage.statusCode()).isEqualTo(200);
    assertThat(listPage.body()).contains(order.getProcessInstanceId());

    final HttpResponse<String> detailPage = get("/instances/" + order.getProcessInstanceId());
    assertThat(detailPage.statusCode()).isEqualTo(200);
    assertThat(detailPage.body()).contains("CUST-004").contains("Manager Approval");

    harness.completeSingleTask(order.getProcessInstanceId(), "managers", Map.of("approved", true));
    harness.assertThat(order.getProcessInstanceId()).hasEndedAt("endEventAccepted");
  }

  @Test
  void diagramRouteReportsNoGraphicalNotationForThisProcess() throws Exception {
    final Order order = orderProcessService.startOrderProcess("CUST-005", new BigDecimal("30.00"));
    harness.awaitTaskForCandidateGroup(
        order.getProcessInstanceId(), "managers", Duration.ofSeconds(15));

    final HttpResponse<String> diagram =
        get("/instances/" + order.getProcessInstanceId() + "/diagram.png");

    assertThat(diagram.statusCode()).isEqualTo(422);
    assertThat(diagram.body()).contains("graphical notation");

    harness.completeSingleTask(order.getProcessInstanceId(), "managers", Map.of("approved", true));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + debugUiServer.port() + path))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
