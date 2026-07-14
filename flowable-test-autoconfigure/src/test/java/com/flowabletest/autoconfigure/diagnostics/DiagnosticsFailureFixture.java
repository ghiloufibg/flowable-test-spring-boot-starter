package com.flowabletest.autoconfigure.diagnostics;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deliberately fails a real BPMN assertion. Not itself a test of this module -- its name doesn't
 * match Surefire's default test-class pattern on purpose, so it never runs (or fails the build) as
 * part of this module's own Surefire execution. {@link FlowableProcessDiagnosticsExtensionTest}
 * runs it programmatically via {@code EngineTestKit} and inspects the resulting failure to prove
 * {@code FlowableProcessDiagnosticsExtension} actually attaches BPMN diagnostics to it.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
public class DiagnosticsFailureFixture {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired ProcessTestHarness harness;

  @BeforeEach
  void deployDiagnosticsProcess() {
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
  }

  @Test
  public void failsOnPurposeWhileTheProcessIsStillActive() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("diagnosticsProcess");
    harness.assertThat(instance.getId()).hasEndedAt("endEvent");
  }
}
