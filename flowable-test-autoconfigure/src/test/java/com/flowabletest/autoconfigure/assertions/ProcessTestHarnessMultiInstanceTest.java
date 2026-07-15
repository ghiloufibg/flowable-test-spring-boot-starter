package com.flowabletest.autoconfigure.assertions;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link ProcessTestHarness#activeExecutionCount} and {@link
 * ProcessTestHarness#awaitActivityCount} against a genuine parallel multi-instance wait state.
 * {@code shipment-orchestration.bpmn20.xml}'s own multi-instance activity in {@code
 * flowable-test-example} is a fully automated service task with no observable wait state to poll
 * for, so this module carries its own dedicated multi-instance user-task fixture instead.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessTestHarnessMultiInstanceTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired TaskService taskService;
  @Autowired ProcessTestHarness harness;

  @BeforeEach
  void deployFixtureProcess() {
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("multiInstanceReviewProcess")
            .count()
        == 0) {
      repositoryService
          .createDeployment()
          .addClasspathResource("processes/multi-instance-review.bpmn20.xml")
          .deploy();
    }
  }

  @Test
  void activeExecutionCountDecreasesAsEachParallelBranchIsCompleted() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "multiInstanceReviewProcess", Map.of("itemCount", 3));

    final List<Execution> executions =
        harness.awaitActivityCount(instance.getId(), "reviewItemTask", 3, Duration.ofSeconds(5));
    assertThat(executions).hasSize(3);
    assertThat(harness.activeExecutionCount(instance.getId(), "reviewItemTask")).isEqualTo(3);

    completeOneReviewTask(instance.getId());

    harness.awaitActivityCount(instance.getId(), "reviewItemTask", 2, Duration.ofSeconds(5));
    assertThat(harness.activeExecutionCount(instance.getId(), "reviewItemTask")).isEqualTo(2);

    // Each completion must settle (the multi-instance parent execution's own bookkeeping update)
    // before the next one starts -- completing two branches back-to-back with no await between
    // them races that same parent execution row and can throw a FlowableOptimisticLockingException.
    completeOneReviewTask(instance.getId());
    harness.awaitActivityCount(instance.getId(), "reviewItemTask", 1, Duration.ofSeconds(5));

    completeOneReviewTask(instance.getId());

    harness.awaitEnded(instance.getId(), Duration.ofSeconds(5));
    harness.assertThat(instance.getId()).hasEndedAt("endEvent");
  }

  private void completeOneReviewTask(String processInstanceId) {
    final Task task =
        taskService
            .createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskCandidateGroup("reviewers")
            .list()
            .get(0);
    taskService.complete(task.getId());
  }
}
