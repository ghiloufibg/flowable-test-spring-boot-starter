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
 * Proves {@link ProcessInstanceTracker} stops tracking new process instance IDs once {@code
 * flowable.test.diagnostics.max-tracked-process-instances} is reached, and reports how many were
 * left out -- so a test that starts an unusually large number of instances can't turn a single
 * failure into an unbounded number of diagnostics queries. Uses its own Spring context (distinct
 * property value from {@link ProcessInstanceTrackerTest}) since the limit is fixed at bean-creation
 * time.
 */
@FlowableProcessTest(
    classes = SampleFlowableApplication.class,
    properties = "flowable.test.diagnostics.max-tracked-process-instances=2")
class ProcessInstanceTrackerTrackingLimitTest {

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
  void stopsTrackingOnceTheLimitIsReachedAndReportsHowManyWereOmitted() {
    final String firstId = runtimeService.startProcessInstanceByKey("diagnosticsProcess").getId();
    final String secondId = runtimeService.startProcessInstanceByKey("diagnosticsProcess").getId();
    runtimeService.startProcessInstanceByKey("diagnosticsProcess");
    runtimeService.startProcessInstanceByKey("diagnosticsProcess");

    assertThat(tracker.trackedProcessInstanceIds()).containsExactly(firstId, secondId);
    assertThat(tracker.omittedProcessInstanceCount()).isEqualTo(2);
  }
}
