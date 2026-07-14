package com.flowabletest.autoconfigure.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
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

  @Test
  void awaitTaskForCandidateGroupPollsUntilTheTaskAppears() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

    final Task task =
        harness.awaitTaskForCandidateGroup(instance.getId(), "reviewers", Duration.ofSeconds(5));

    assertThat(task.getName()).isEqualTo("Review");
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
}
