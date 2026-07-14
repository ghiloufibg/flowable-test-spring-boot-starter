package com.flowabletest.autoconfigure.assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.assertions.ProcessInstanceAssert;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.util.List;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves a failure inside diagnostics collection itself can never replace the real assertion
 * failure being reported. {@code diagnosticsCollector.collect(...)} is given a {@code null} {@code
 * RuntimeService} so it throws deterministically (no mocking framework needed) the moment {@link
 * ProcessInstanceAssert} tries to enrich a failure with it.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessInstanceAssertTest {

  @Autowired RepositoryService repositoryService;
  @Autowired RuntimeService runtimeService;
  @Autowired TaskService taskService;
  @Autowired HistoryService historyService;
  @Autowired ManagementService managementService;
  @Autowired ProcessTestHarness harness;

  @BeforeEach
  void deployHelloProcess() {
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("helloProcess")
            .count()
        == 0) {
      repositoryService
          .createDeployment()
          .addClasspathResource("processes/hello.bpmn20.xml")
          .deploy();
    }
  }

  @Test
  void aDiagnosticsCollectionFailureDoesNotMaskTheOriginalAssertionFailure() {
    final ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");
    harness.completeSingleTask(instance.getId(), "reviewers", Map.of());

    final ProcessDiagnosticsCollector brokenCollector =
        new ProcessDiagnosticsCollector(
            null, taskService, historyService, managementService, 20, 500, true, List.of());
    final ProcessInstanceAssert brokenAssert =
        new ProcessInstanceAssert(
            instance.getId(), runtimeService, historyService, brokenCollector);

    assertThatThrownBy(brokenAssert::isActive)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("to still be active, but it has ended")
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
  }
}
