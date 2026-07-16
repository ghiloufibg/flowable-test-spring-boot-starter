package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsFormatter;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * {@code GET /instances/{id}/diagnostics.txt} -- the same plain-text rendering
 * {@link ProcessDiagnosticsFormatter} produces for a failed test's attached diagnostics, so the
 * debug UI and the on-failure output are always the same text instead of two independent
 * renderings drifting apart.
 */
final class DiagnosticsTextHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/instances/";
  private static final String PATH_SUFFIX = "/diagnostics.txt";

  private final ProcessDiagnosticsCollector processDiagnosticsCollector;

  DiagnosticsTextHandler(ProcessDiagnosticsCollector processDiagnosticsCollector) {
    this.processDiagnosticsCollector = processDiagnosticsCollector;
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
    HttpResponses.sendPlainText(exchange, 200, ProcessDiagnosticsFormatter.format(report));
  }
}
