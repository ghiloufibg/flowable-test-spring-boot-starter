package com.flowabletest.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link ProcessDiagnosticsCollector} correctly turns a process instance ID into a
 * diagnostics snapshot for both active and already-ended instances, and surfaces dead-letter job
 * failures.
 */
@FlowableProcessTest(
    classes = SampleFlowableApplication.class,
    properties = "flowable.history-level=full")
class ProcessDiagnosticsCollectorTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired TaskService taskService;
  @Autowired HistoryService historyService;
  @Autowired ManagementService managementService;
  @Autowired ProcessDiagnosticsCollector collector;
  @Autowired ProcessTestHarness harness;
  @Autowired ProcessInstanceTracker processInstanceTracker;

  @BeforeEach
  void deployDiagnosticsProcesses() {
    deployOnce("diagnosticsProcess", "processes/diagnostics.bpmn20.xml");
    deployOnce("diagnosticsAsyncFailProcess", "processes/diagnostics-async-fail.bpmn20.xml");
    deployOnce("diagnosticsGatewayProcess", "processes/diagnostics-gateway.bpmn20.xml");
    deployOnce("diagnosticsParentProcess", "processes/diagnostics-call-activity.bpmn20.xml");
    deployOnce("timerBoundaryProcess", "processes/timer-boundary.bpmn20.xml");
    deployOnce("multiInstanceReviewProcess", "processes/multi-instance-review.bpmn20.xml");
  }

  private void deployOnce(String processDefinitionKey, String classpathResource) {
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey(processDefinitionKey)
            .count()
        == 0) {
      repositoryService.createDeployment().addClasspathResource(classpathResource).deploy();
    }
  }

  @Test
  void reportsTheOriginatingTestForASynchronouslyStartedInstance() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("diagnosticsProcess");

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.testOrigin())
        .isEqualTo(
            "ProcessDiagnosticsCollectorTest.reportsTheOriginatingTestForASynchronouslyStartedInstance");
  }

  @Test
  void reportsCurrentActivityVariablesAndPendingTasksForAnActiveInstance() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess", Map.of("orderId", 42, "amount", "199.99"));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.active()).isTrue();
    assertThat(report.processDefinitionKey()).isEqualTo("diagnosticsProcess");
    assertThat(report.currentActivities())
        .extracting(ProcessDiagnosticsReport.ActivityInfo::activityId)
        .containsExactly("reviewTask");
    assertThat(report.variables()).containsEntry("orderId", "42").containsEntry("amount", "199.99");
    assertThat(report.pendingTasks())
        .hasSize(1)
        .first()
        .satisfies(
            task -> {
              assertThat(task.name()).isEqualTo("Review");
              assertThat(task.candidateGroups()).containsExactly("reviewers");
            });
  }

  @Test
  void fallsBackToHistoryOnceTheInstanceHasEnded() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagnosticsProcess", Map.of("orderId", 7));
    harness.completeSingleTask(instance.getId(), "reviewers", Map.of());

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.active()).isFalse();
    assertThat(report.currentActivities()).isEmpty();
    assertThat(report.variables()).containsEntry("orderId", "7");
    // Unordered: all three run synchronously in the same command, so their start timestamps can
    // legitimately tie at whatever resolution the underlying DB stores (H2 in this suite) --
    // sequence-flow noise being filtered out is what this assertion actually cares about.
    assertThat(report.activityTrail())
        .extracting(ProcessDiagnosticsReport.ActivityTrailEntry::activityId)
        .containsExactlyInAnyOrder("startEvent", "reviewTask", "endEvent");
  }

  @Test
  void truncatesVariableValuesLongerThanTheConfiguredMaximum() {
    final ProcessDiagnosticsCollector shortLimitCollector =
        new ProcessDiagnosticsCollector(
            runtimeService,
            taskService,
            historyService,
            managementService,
            repositoryService,
            processInstanceTracker,
            20,
            10,
            50,
            true,
            List.of());
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess", Map.of("payload", "a-value-much-longer-than-ten-characters"));

    final ProcessDiagnosticsReport report = shortLimitCollector.collect(instance.getId());

    assertThat(report.variables().get("payload")).contains("truncated");
  }

  @Test
  void redactsVariablesWhoseNameMatchesAConfiguredPattern() {
    final ProcessDiagnosticsCollector redactingCollector =
        new ProcessDiagnosticsCollector(
            runtimeService,
            taskService,
            historyService,
            managementService,
            repositoryService,
            processInstanceTracker,
            20,
            500,
            50,
            true,
            List.of("password", "token"));
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess",
            Map.of("orderId", 42, "userPassword", "hunter2", "authToken", "abc123"));

    final ProcessDiagnosticsReport report = redactingCollector.collect(instance.getId());

    assertThat(report.variables())
        .containsEntry("orderId", "42")
        .containsEntry("userPassword", "[REDACTED]")
        .containsEntry("authToken", "[REDACTED]");
  }

  @Test
  void theAutoConfiguredCollectorRedactsCommonSecretLikeVariableNamesByDefault() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess", Map.of("orderId", 1, "password", "hunter2"));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.variables())
        .containsEntry("orderId", "1")
        .containsEntry("password", "[REDACTED]");
  }

  @Test
  void includesDeadLetterJobFailures() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagnosticsAsyncFailProcess");
    final Job pendingJob =
        managementService.createJobQuery().processInstanceId(instance.getId()).singleResult();
    managementService.moveJobToDeadLetterJob(pendingJob.getId());

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.failedJobs())
        .hasSize(1)
        .first()
        .satisfies(job -> assertThat(job.elementId()).isEqualTo("riskyTask"));
  }

  @Test
  void reportsVariableHistoryInChronologicalOrder() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagnosticsProcess", Map.of("orderId", 1));
    runtimeService.setVariable(instance.getId(), "orderId", 2);

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.variableHistory())
        .extracting(
            ProcessDiagnosticsReport.VariableHistoryEntry::variableName,
            ProcessDiagnosticsReport.VariableHistoryEntry::value)
        .containsExactly(Tuple.tuple("orderId", "1"), Tuple.tuple("orderId", "2"));
  }

  @Test
  void reportsGatewayTraceWithTheTakenFlowMarkedAmongAllOutgoingFlows() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsGatewayProcess", Map.of("amount", 150));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.gatewayTrace())
        .hasSize(1)
        .first()
        .satisfies(
            gateway -> {
              assertThat(gateway.gatewayId()).isEqualTo("amountGateway");
              assertThat(gateway.outgoingFlows())
                  .hasSize(2)
                  .anySatisfy(
                      flow -> {
                        assertThat(flow.sequenceFlowId()).isEqualTo("flowHigh");
                        assertThat(flow.taken()).isTrue();
                        assertThat(flow.conditionExpression()).contains("amount >= 100");
                      })
                  .anySatisfy(
                      flow -> {
                        assertThat(flow.sequenceFlowId()).isEqualTo("flowLow");
                        assertThat(flow.taken()).isFalse();
                      });
            });
  }

  @Test
  void reportsPendingTimerJobsSeparatelyFromFailedJobs() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("timerBoundaryProcess");

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.pendingJobs())
        .hasSize(1)
        .first()
        .satisfies(
            job -> {
              assertThat(job.elementId()).isEqualTo("confirmationTimeoutBoundary");
              assertThat(job.jobType()).isEqualTo("timer");
              assertThat(job.dueDate()).isNotNull();
            });
    assertThat(report.failedJobs()).isEmpty();
  }

  @Test
  void reportsPendingAsyncJobBeforeItHasFailed() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagnosticsAsyncFailProcess");

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.pendingJobs())
        .hasSize(1)
        .first()
        .satisfies(job -> assertThat(job.elementId()).isEqualTo("riskyTask"));
  }

  @Test
  void reportsMultiInstanceProgressForACurrentlyActiveMultiInstanceActivity() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "multiInstanceReviewProcess", Map.of("itemCount", 3));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.currentActivities())
        .hasSize(1)
        .first()
        .satisfies(
            activity -> {
              assertThat(activity.activityId()).isEqualTo("reviewItemTask");
              assertThat(activity.multiInstanceProgress()).isNotNull();
              assertThat(activity.multiInstanceProgress().nrOfInstances()).isEqualTo(3);
              assertThat(activity.multiInstanceProgress().nrOfActiveInstances()).isEqualTo(3);
              assertThat(activity.multiInstanceProgress().nrOfCompletedInstances()).isEqualTo(0);
            });
  }

  @Test
  void reportsTheSpawnedChildInstanceIdForAnActiveCallActivity() {
    final ProcessInstance parent =
        runtimeService.startProcessInstanceByKey("diagnosticsParentProcess");
    final ProcessInstance child =
        runtimeService
            .createProcessInstanceQuery()
            .processDefinitionKey("diagnosticsChildProcess")
            .singleResult();

    final ProcessDiagnosticsReport report = collector.collect(parent.getId());

    assertThat(report.currentActivities())
        .hasSize(1)
        .first()
        .satisfies(
            activity -> {
              assertThat(activity.activityId()).isEqualTo("delegateReviewCall");
              assertThat(activity.calledProcessInstanceId()).isEqualTo(child.getId());
            });
  }

  @Test
  void rendersObjectTypeVariablesAsJsonInsteadOfAToStringHashCode() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess", Map.of("order", new OrderSnapshot("CUST-1", 3)));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.variables().get("order"))
        .contains("\"customerId\":\"CUST-1\"")
        .contains("\"itemCount\":3");
  }

  @Test
  void rendersListAndMapVariablesAsJson() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess",
            Map.of("tags", List.of("urgent", "vip"), "metadata", Map.of("region", "EU")));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.variables().get("tags")).isEqualTo("[\"urgent\",\"vip\"]");
    assertThat(report.variables().get("metadata")).isEqualTo("{\"region\":\"EU\"}");
  }

  @Test
  void fallsBackToToStringWhenJacksonCannotSerializeTheValue() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagnosticsProcess", Map.of("broken", new PoisonedGetterVariable()));

    final ProcessDiagnosticsReport report = collector.collect(instance.getId());

    assertThat(report.variables().get("broken"))
        .isEqualTo("PoisonedGetterVariable[fallback-toString]");
  }

  private record OrderSnapshot(String customerId, int itemCount) implements Serializable {}

  private static final class PoisonedGetterVariable implements Serializable {

    public String getValue() {
      throw new IllegalStateException("Jackson must not see this");
    }

    @Override
    public String toString() {
      return "PoisonedGetterVariable[fallback-toString]";
    }
  }
}
