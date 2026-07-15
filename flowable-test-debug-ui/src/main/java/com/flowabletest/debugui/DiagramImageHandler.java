package com.flowabletest.debugui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * {@code GET /instances/{id}/diagram.png} -- the current-activity-highlighted BPMN diagram, via
 * {@link ProcessInstanceDiagramRenderer}.
 */
final class DiagramImageHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/instances/";
  private static final String PATH_SUFFIX = "/diagram.png";

  private final ProcessInstanceDiagramRenderer diagramRenderer;

  DiagramImageHandler(ProcessInstanceDiagramRenderer diagramRenderer) {
    this.diagramRenderer = diagramRenderer;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    final String processInstanceId =
        path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
    try {
      HttpResponses.sendPng(exchange, diagramRenderer.renderPng(processInstanceId));
    } catch (final UnknownProcessInstanceException e) {
      HttpResponses.sendPlainText(exchange, 404, e.getMessage());
    } catch (final NoGraphicalNotationException e) {
      HttpResponses.sendPlainText(exchange, 422, e.getMessage());
    }
  }
}
