package com.flowabletest.autoconfigure.assertions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.time.Duration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link ProcessTestHarness#triggerSignal}, {@link ProcessTestHarness#triggerMessage}, and
 * {@link ProcessTestHarness#forceTimerDue} resume a process instance waiting on an
 * intermediate/boundary event, against real signal-catch, message-catch, and timer-boundary BPMN
 * fixtures -- no flow exercised elsewhere in this repo uses these event types. {@code
 * forceTimerDue} deliberately never calls {@code ManagementService#executeJob} itself; the moved
 * job is left for this module's already-running async executor to pick up, exactly as production
 * would, to avoid racing that executor for the same job.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessTestHarnessEventTriggerTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired ProcessTestHarness harness;

  @BeforeEach
  void deployFixtureProcesses() {
    deployOnce("signalCatchProcess", "processes/signal-catch.bpmn20.xml");
    deployOnce("messageCatchProcess", "processes/message-catch.bpmn20.xml");
    deployOnce("timerBoundaryProcess", "processes/timer-boundary.bpmn20.xml");
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
  void triggerSignalResumesTheWaitingExecution() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("signalCatchProcess");
    harness.awaitActivity(instance.getId(), "awaitApprovalSignal", Duration.ofSeconds(5));

    harness.triggerSignal("approvalSignal");

    harness.awaitEnded(instance.getId(), Duration.ofSeconds(5));
    harness.assertThat(instance.getId()).hasEndedAt("endEvent");
  }

  @Test
  void triggerMessageResumesTheWaitingExecution() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("messageCatchProcess");
    harness.awaitActivity(instance.getId(), "awaitApprovalMessage", Duration.ofSeconds(5));

    harness.triggerMessage(instance.getId(), "approvalMessage");

    harness.awaitEnded(instance.getId(), Duration.ofSeconds(5));
    harness.assertThat(instance.getId()).hasEndedAt("endEvent");
  }

  @Test
  void triggerMessageFailsLoudlyWhenNoExecutionIsWaitingOnIt() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("messageCatchProcess");
    harness.awaitActivity(instance.getId(), "awaitApprovalMessage", Duration.ofSeconds(5));

    assertThatThrownBy(() -> harness.triggerMessage(instance.getId(), "noSuchMessage"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Expected exactly one execution");
  }

  @Test
  void forceTimerDueLetsTheAlreadyRunningAsyncExecutorDriveTheEscalationBranch() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("timerBoundaryProcess");
    harness.awaitTaskForCandidateGroup(instance.getId(), "reviewers", Duration.ofSeconds(5));

    harness.forceTimerDue(instance.getId(), "confirmationTimeoutBoundary");

    harness.awaitEnded(instance.getId(), Duration.ofSeconds(15));
    harness.assertThat(instance.getId()).hasEndedAt("timedOutEndEvent");
  }

  @Test
  void forceTimerDueFailsLoudlyWhenNoMatchingTimerJobExists() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("timerBoundaryProcess");
    harness.awaitTaskForCandidateGroup(instance.getId(), "reviewers", Duration.ofSeconds(5));

    assertThatThrownBy(() -> harness.forceTimerDue(instance.getId(), "noSuchActivity"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Expected exactly one timer job");
  }
}
