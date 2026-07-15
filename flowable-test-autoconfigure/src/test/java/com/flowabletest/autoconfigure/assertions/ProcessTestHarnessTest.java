package com.flowabletest.autoconfigure.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link ProcessTestHarness} and {@code @FlowableProcessTest} work end to end against a real
 * Flowable engine (pinned test-scope dependency; never leaks to consumers) and a real, if tiny,
 * BPMN process, plus an implicit check that {@code FlowableCompatibilityGuardAutoConfiguration}
 * doesn't reject a supported (7.1.0) engine: if it did, the context below would fail to start.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessTestHarnessTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired TaskService taskService;
  @Autowired HistoryService historyService;
  @Autowired ManagementService managementService;
  @Autowired ProcessTestHarness harness;

  @BeforeEach
  void deployFixtureProcesses() {
    deployOnce("helloProcess", "processes/hello.bpmn20.xml");
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
  void completingTheSingleTaskEndsTheProcess() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    harness.assertThat(instance.getId()).isActive();

    final Task task = harness.completeSingleTask(instance.getId(), "reviewers", Map.of());
    assertThat(task.getName()).isEqualTo("Review");

    harness.assertThat(instance.getId()).hasEndedAt("endEvent");
  }

  /**
   * {@code harness-only.bpmn20.xml} lives under {@code processes-harness-only/}, a root never
   * scanned by Flowable's own default {@code classpath*:/processes/} location and never deployed by
   * any other mechanism -- so starting its process instance below only succeeds if {@link
   * ProcessTestHarness#deployProcess} itself did the deployment. Uses its own harness instance
   * constructed with that alternate {@code processesRoot}, mirroring how {@link
   * #aDiagnosticsCollectionFailureDoesNotMaskTheOriginalHarnessFailure} already constructs a
   * harness directly rather than through the Spring-wired bean.
   */
  @Test
  void completeOneTaskForCandidateGroupFailsLoudlyWhenNoTaskExistsForTheGroup() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    assertThatThrownBy(
            () ->
                harness.completeOneTaskForCandidateGroup(
                    instance.getId(), "no-such-group", Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-such-group")
        .hasMessageContaining("but found none");
  }

  @Test
  void deployProcessDeploysABpmnFileFromTheConfiguredProcessesRoot() {
    final ProcessTestHarness rootScopedHarness =
        new ProcessTestHarness(
            runtimeService,
            taskService,
            historyService,
            managementService,
            repositoryService,
            "classpath:processes-harness-only",
            null);

    rootScopedHarness.deployProcess("harness-only");

    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("harnessOnlyProcess");
    assertThat(instance).isNotNull();
  }

  @Test
  void awaitTaskForCandidateGroupPollsUntilTheTaskAppears() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    final Task task =
        harness.awaitTaskForCandidateGroup(instance.getId(), "reviewers", Duration.ofSeconds(5));

    assertThat(task.getName()).isEqualTo("Review");
  }

  @Test
  void currentActivityIdsReflectsTheActiveWaitState() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    assertThat(harness.currentActivityIds(instance.getId())).containsExactly("reviewTask");
  }

  @Test
  void awaitActivityWaitsUntilAnExecutionArrivesAtTheGivenNode() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    final List<Execution> executions =
        harness.awaitActivity(instance.getId(), "reviewTask", Duration.ofSeconds(5));

    assertThat(executions).hasSize(1);
  }

  @Test
  void setVariablesUpdatesAnActiveProcessInstance() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    harness.setVariables(instance.getId(), Map.of("orderId", "abc-123"));

    harness.assertThat(instance.getId()).hasVariable("orderId", "abc-123");
  }

  /**
   * A dead-letter job never resolves on its own, so without a fail-fast check {@code awaitEnded}
   * would otherwise have no choice but to wait out its entire timeout for a state that will never
   * arrive. Uses a generous 30-second timeout specifically so this test can prove the fail-fast
   * path fires within a small fraction of it, rather than being indistinguishable from a real
   * timeout.
   */
  @Test
  void awaitEndedFailsFastOnADeadLetterJobInsteadOfWaitingOutTheFullTimeout() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagnosticsAsyncFailProcess");
    final Job pendingJob =
        managementService.createJobQuery().processInstanceId(instance.getId()).singleResult();
    managementService.moveJobToDeadLetterJob(pendingJob.getId());

    final Instant before = Instant.now();
    assertThatThrownBy(() -> harness.awaitEnded(instance.getId(), Duration.ofSeconds(30)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("dead-letter job");

    assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(5));
  }

  /**
   * A diagnostics-collection failure (a flaky DB connection is a realistic case, right at the
   * moment something has already gone wrong) must never replace the real harness failure with an
   * unrelated exception. {@code diagnosticsCollector.collect(...)} is given a {@code null} {@code
   * RuntimeService} so it throws deterministically (no mocking framework needed).
   */
  @Test
  void aDiagnosticsCollectionFailureDoesNotMaskTheOriginalHarnessFailure() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");
    final ProcessDiagnosticsCollector brokenCollector =
        new ProcessDiagnosticsCollector(
            null, taskService, historyService, managementService, 20, 500, true, List.of());
    final ProcessTestHarness brokenHarness =
        new ProcessTestHarness(
            runtimeService,
            taskService,
            historyService,
            managementService,
            repositoryService,
            "classpath:processes",
            brokenCollector);

    assertThatThrownBy(() -> brokenHarness.findSingleTask(instance.getId(), "no-such-group"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Expected exactly one task")
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
  }
}
