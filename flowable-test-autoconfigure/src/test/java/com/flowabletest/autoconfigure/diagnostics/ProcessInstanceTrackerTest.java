package com.flowabletest.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link ProcessInstanceTracker} is actually wired onto the engine's event dispatcher (real
 * {@code PROCESS_STARTED} events reach it, in start order, including for concurrently-started
 * instances) and that {@link ProcessInstanceTracker#reset()} clears it -- the mechanism {@code
 * FlowableProcessDiagnosticsExtension} relies on to scope diagnostics to exactly one test method.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessInstanceTrackerTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired ProcessInstanceTracker tracker;

  @BeforeEach
  void deployDiagnosticsProcessAndResetTracker() {
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("diagnosticsProcess")
            .count()
        == 0) {
      repositoryService
          .createDeployment()
          .addClasspathResource("processes/diagnostics.bpmn20.xml")
          .deploy();
    }
    // Guards against pollution from an earlier test class sharing this context/engine.
    tracker.reset();
  }

  @Test
  void tracksEveryProcessInstanceStartedSinceTheLastReset() {
    final String firstId = runtimeService.startProcessInstanceByKey("diagnosticsProcess").getId();
    final String secondId = runtimeService.startProcessInstanceByKey("diagnosticsProcess").getId();

    assertThat(tracker.trackedProcessInstanceIds()).containsExactly(firstId, secondId);
  }

  @Test
  void resetClearsPreviouslyTrackedProcessInstances() {
    runtimeService.startProcessInstanceByKey("diagnosticsProcess");

    tracker.reset();

    assertThat(tracker.trackedProcessInstanceIds()).isEmpty();
  }
}
