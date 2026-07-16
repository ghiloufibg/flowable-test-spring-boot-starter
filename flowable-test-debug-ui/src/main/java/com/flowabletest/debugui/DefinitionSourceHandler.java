package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import org.flowable.engine.RepositoryService;

/**
 * {@code GET /instances/{id}/definition.xml} -- the exact deployed BPMN XML the running process
 * instance was parsed from, streamed via {@link RepositoryService#getResourceAsStream(String,
 * String)} against the deployment ID and resource name {@link ProcessDiagnosticsCollector} already
 * resolved. A separate, cacheable route rather than embedding the XML in the main detail page --
 * unlike variables/tasks/activity trail, the deployed definition never changes for the lifetime of
 * a process instance, so it doesn't need to be re-sent on every 3-second auto-refresh.
 */
final class DefinitionSourceHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/instances/";
  private static final String PATH_SUFFIX = "/definition.xml";

  private final ProcessDiagnosticsCollector processDiagnosticsCollector;
  private final RepositoryService repositoryService;

  DefinitionSourceHandler(
      ProcessDiagnosticsCollector processDiagnosticsCollector,
      RepositoryService repositoryService) {
    this.processDiagnosticsCollector = processDiagnosticsCollector;
    this.repositoryService = repositoryService;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    final String path = exchange.getRequestURI().getPath();
    final String processInstanceId =
        path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
    final ProcessDiagnosticsReport report = processDiagnosticsCollector.collect(processInstanceId);
    if (report.processDefinitionKey() == null) {
      HttpResponses.sendPlainText(
          exchange, 404, "No process instance found for ID <" + processInstanceId + ">");
      return;
    }
    if (report.deploymentId() == null || report.bpmnResourceName() == null) {
      HttpResponses.sendPlainText(
          exchange,
          404,
          "No deployed BPMN resource found for process instance <" + processInstanceId + ">");
      return;
    }
    try (InputStream resource =
        repositoryService.getResourceAsStream(report.deploymentId(), report.bpmnResourceName())) {
      if (resource == null) {
        HttpResponses.sendPlainText(
            exchange, 404, "Resource <" + report.bpmnResourceName() + "> not found in deployment");
        return;
      }
      HttpResponses.sendXml(exchange, 200, resource.readAllBytes());
    }
  }
}
