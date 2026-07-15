package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityTrailEntry;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.PendingTaskInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /instances/{id}} -- diagram, variables, pending tasks, and activity trail for one
 * process instance, all sourced from {@link ProcessDiagnosticsCollector#collect(String)} rather
 * than re-querying the engine. Refreshes every few seconds since this is a live view, not a
 * point-in-time diagnostics attachment.
 */
final class InstanceDetailHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/instances/";

  private final ProcessDiagnosticsCollector processDiagnosticsCollector;

  InstanceDetailHandler(ProcessDiagnosticsCollector processDiagnosticsCollector) {
    this.processDiagnosticsCollector = processDiagnosticsCollector;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    final String processInstanceId =
        exchange.getRequestURI().getPath().substring(PATH_PREFIX.length());
    final ProcessDiagnosticsReport report = processDiagnosticsCollector.collect(processInstanceId);
    if (report.processDefinitionKey() == null) {
      HttpResponses.sendPlainText(
          exchange, 404, "No process instance found for ID <" + processInstanceId + ">");
      return;
    }
    HttpResponses.sendHtml(exchange, 200, renderPage(processInstanceId, report));
  }

  private static String renderPage(String processInstanceId, ProcessDiagnosticsReport report) {
    final String escapedId = Html.escape(processInstanceId);
    return """
        <!doctype html>
        <html>
        <head>
          <title>Flowable Test debug UI - %s</title>
          <meta http-equiv="refresh" content="3">
        </head>
        <body>
        <p><a href="/">&laquo; all instances</a></p>
        <h1>%s</h1>
        <p>%s, version %s, business key %s</p>
        <h2>Diagram</h2>
        <img src="/instances/%s/diagram.png" alt="BPMN diagram">
        <h2>Variables</h2>
        %s
        <h2>Pending tasks</h2>
        %s
        <h2>Activity trail</h2>
        %s
        </body>
        </html>
        """
        .formatted(
            escapedId,
            escapedId,
            report.active() ? "active" : "ended",
            report.processDefinitionVersion(),
            Html.escape(report.businessKey()),
            escapedId,
            renderVariables(report.variables()),
            renderTasks(report.pendingTasks()),
            renderActivityTrail(report.activityTrail()));
  }

  private static String renderVariables(Map<String, String> variables) {
    if (variables.isEmpty()) {
      return "<p>(none)</p>";
    }
    final StringBuilder rows = new StringBuilder("<table><tr><th>Name</th><th>Value</th></tr>");
    variables.forEach(
        (name, value) ->
            rows.append("<tr><td>")
                .append(Html.escape(name))
                .append("</td><td>")
                .append(Html.escape(value))
                .append("</td></tr>"));
    return rows.append("</table>").toString();
  }

  private static String renderTasks(List<PendingTaskInfo> tasks) {
    if (tasks.isEmpty()) {
      return "<p>(none)</p>";
    }
    final StringBuilder items = new StringBuilder("<ul>");
    for (final PendingTaskInfo task : tasks) {
      items
          .append("<li>")
          .append(Html.escape(task.name()))
          .append(" (assignee: ")
          .append(Html.escape(task.assignee()))
          .append(", candidate groups: ")
          .append(Html.escape(String.join(", ", task.candidateGroups())))
          .append(")</li>");
    }
    return items.append("</ul>").toString();
  }

  private static String renderActivityTrail(List<ActivityTrailEntry> trail) {
    if (trail.isEmpty()) {
      return "<p>(none)</p>";
    }
    final StringBuilder items = new StringBuilder("<ol>");
    for (final ActivityTrailEntry entry : trail) {
      items
          .append("<li>")
          .append(
              Html.escape(entry.activityName() != null ? entry.activityName() : entry.activityId()))
          .append(" (")
          .append(entry.startTime())
          .append(entry.endTime() != null ? " to " + entry.endTime() : ", still running")
          .append(")</li>");
    }
    return items.append("</ol>").toString();
  }
}
