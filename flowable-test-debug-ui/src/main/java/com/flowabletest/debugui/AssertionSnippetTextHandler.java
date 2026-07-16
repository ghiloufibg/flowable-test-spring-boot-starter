package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessAssertionSnippetFormatter;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * {@code GET /instances/{id}/assertion.txt} -- a ready-to-paste {@code ProcessInstanceAssert}
 * snippet reflecting this instance's current state, so confirming "this is the state I expect" in
 * the debug UI can become working test code in one copy-paste.
 */
final class AssertionSnippetTextHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/instances/";
  private static final String PATH_SUFFIX = "/assertion.txt";

  private final ProcessDiagnosticsCollector processDiagnosticsCollector;

  AssertionSnippetTextHandler(ProcessDiagnosticsCollector processDiagnosticsCollector) {
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
    HttpResponses.sendPlainText(exchange, 200, ProcessAssertionSnippetFormatter.format(report));
  }
}
