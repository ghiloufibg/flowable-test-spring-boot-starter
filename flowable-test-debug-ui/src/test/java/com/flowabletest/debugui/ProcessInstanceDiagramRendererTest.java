package com.flowabletest.debugui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.debugui.testapp.SampleFlowableApplication;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@link ProcessInstanceDiagramRenderer} actually renders a PNG for an active instance, an
 * ended instance (unhighlighted), and fails predictably for an unknown ID -- against a real
 * deployed process with a hand-authored {@code BPMNDiagram} section, since no existing BPMN fixture
 * elsewhere in this repo has one.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessInstanceDiagramRendererTest {

  private static final byte[] PNG_MAGIC_HEADER = {
    (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
  };

  @Autowired ProcessEngine processEngine;
  @Autowired RuntimeService runtimeService;
  @Autowired RepositoryService repositoryService;
  @Autowired HistoryService historyService;
  @Autowired TaskService taskService;

  private ProcessInstanceDiagramRenderer renderer;

  @BeforeEach
  void deployFixtureProcessesAndCreateRenderer() {
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("diagramFixtureProcess")
            .count()
        == 0) {
      repositoryService
          .createDeployment()
          .addClasspathResource("processes/diagram-fixture.bpmn20.xml")
          .deploy();
    }
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("noDiagramFixtureProcess")
            .count()
        == 0) {
      repositoryService
          .createDeployment()
          .addClasspathResource("processes/no-diagram-fixture.bpmn20.xml")
          .deploy();
    }
    renderer =
        new ProcessInstanceDiagramRenderer(
            processEngine, runtimeService, repositoryService, historyService);
  }

  @Test
  void rendersAHighlightedPngForAnActiveInstance() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagramFixtureProcess");

    final byte[] png = renderer.renderPng(instance.getId());

    assertThat(png).startsWith(PNG_MAGIC_HEADER);
  }

  @Test
  void rendersAnUnhighlightedPngForAnEndedInstance() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagramFixtureProcess");
    final Task task =
        taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
    taskService.complete(task.getId(), Map.of());

    final byte[] png = renderer.renderPng(instance.getId());

    assertThat(png).startsWith(PNG_MAGIC_HEADER);
  }

  @Test
  void throwsForAnUnknownProcessInstanceId() {
    assertThatThrownBy(() -> renderer.renderPng("does-not-exist"))
        .isInstanceOf(UnknownProcessInstanceException.class);
  }

  @Test
  void throwsForAProcessDefinitionWithNoGraphicalNotation() {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("noDiagramFixtureProcess");

    assertThatThrownBy(() -> renderer.renderPng(instance.getId()))
        .isInstanceOf(NoGraphicalNotationException.class);
  }
}
