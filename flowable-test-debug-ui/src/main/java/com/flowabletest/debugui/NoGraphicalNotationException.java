package com.flowabletest.debugui;

/**
 * A BPMN process definition with no {@code BPMNDI} section -- {@link
 * org.flowable.bpmn.model.BpmnModel#getLocationMap()} is empty. {@code
 * ProcessDiagramGenerator#generateDiagram} does not check for this itself and throws a bare {@link
 * NullPointerException} from deep inside its canvas-sizing logic instead, which is not something a
 * caller should catch broadly, so {@link ProcessInstanceDiagramRenderer} checks for this case
 * explicitly and throws this instead.
 */
final class NoGraphicalNotationException extends RuntimeException {

  NoGraphicalNotationException(String processDefinitionId) {
    super(
        "Process definition <"
            + processDefinitionId
            + "> has no BPMNDI graphical notation to render a diagram from");
  }
}
