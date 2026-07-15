package com.flowabletest.debugui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;

/**
 * Renders a process instance's BPMN diagram with its currently active activities highlighted,
 * mirroring the mechanism Flowable's own (now-removed from mainline) Admin UI used in {@code
 * ProcessInstanceDiagramResource}: {@link RuntimeService#getActiveActivityIds}, {@link
 * RepositoryService#getBpmnModel}, and {@code
 * ProcessEngineConfiguration#getProcessDiagramGenerator()}. Runs those calls directly against the
 * engine, rather than over REST like the legacy resource did, since this runs in the same JVM as
 * the engine it renders.
 */
final class ProcessInstanceDiagramRenderer {

  private final ProcessEngine processEngine;
  private final RuntimeService runtimeService;
  private final RepositoryService repositoryService;
  private final HistoryService historyService;

  ProcessInstanceDiagramRenderer(
      ProcessEngine processEngine,
      RuntimeService runtimeService,
      RepositoryService repositoryService,
      HistoryService historyService) {
    this.processEngine = processEngine;
    this.runtimeService = runtimeService;
    this.repositoryService = repositoryService;
    this.historyService = historyService;
  }

  /**
   * Renders {@code processInstanceId}'s diagram as PNG bytes -- current activities highlighted
   * while active, unhighlighted once it has ended. Throws {@link UnknownProcessInstanceException}
   * if the ID resolves in neither runtime nor history state, and lets {@link
   * FlowableIllegalArgumentException} propagate unchanged if the process definition has no
   * graphical notation to render -- both are handled by {@link DiagramImageHandler}.
   */
  byte[] renderPng(String processInstanceId) {
    final ProcessInstance instance =
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();

    final List<String> activeActivityIds;
    final String processDefinitionId;
    if (instance != null) {
      activeActivityIds = runtimeService.getActiveActivityIds(processInstanceId);
      processDefinitionId = instance.getProcessDefinitionId();
    } else {
      final HistoricProcessInstance historicInstance =
          historyService
              .createHistoricProcessInstanceQuery()
              .processInstanceId(processInstanceId)
              .singleResult();
      if (historicInstance == null) {
        throw new UnknownProcessInstanceException(processInstanceId);
      }
      activeActivityIds = List.of();
      processDefinitionId = historicInstance.getProcessDefinitionId();
    }

    final BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
    final ProcessDiagramGenerator generator =
        processEngine.getProcessEngineConfiguration().getProcessDiagramGenerator();
    try (InputStream png =
        generator.generateDiagram(bpmnModel, "png", activeActivityIds, List.of(), false)) {
      return png.readAllBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to read generated BPMN diagram bytes", e);
    }
  }
}
