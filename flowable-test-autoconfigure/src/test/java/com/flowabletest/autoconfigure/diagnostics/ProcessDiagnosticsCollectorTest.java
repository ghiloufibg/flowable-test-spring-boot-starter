package com.flowabletest.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.util.List;
import java.util.Map;
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
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessDiagnosticsCollectorTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired TaskService taskService;
  @Autowired HistoryService historyService;
  @Autowired ManagementService managementService;
  @Autowired ProcessDiagnosticsCollector collector;
  @Autowired ProcessTestHarness harness;

  @BeforeEach
  void deployDiagnosticsProcesses() {
    deployOnce("diagnosticsProcess", "processes/diagnostics.bpmn20.xml");
    deployOnce("diagnosticsAsyncFailProcess", "processes/diagnostics-async-fail.bpmn20.xml");
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
            20,
            10,
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
            20,
            500,
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
}
