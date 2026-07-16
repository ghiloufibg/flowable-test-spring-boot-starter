package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code GET /} -- every process instance {@link ProcessInstanceTracker} has tracked since the
 * currently-running test method's last reset, grouped by process definition key with an
 * active/ended status badge per row. {@link ProcessInstanceTracker} is reset before every test
 * method, so this list never spans more than the test method currently executing, even though the
 * server itself lives for the whole Spring context's lifetime.
 */
final class InstanceListHandler implements HttpHandler {

  private static final String UNKNOWN_DEFINITION_LABEL = "(unknown process definition)";

  private final ProcessInstanceTracker processInstanceTracker;
  private final ProcessDiagnosticsCollector processDiagnosticsCollector;

  InstanceListHandler(
      ProcessInstanceTracker processInstanceTracker,
      ProcessDiagnosticsCollector processDiagnosticsCollector) {
    this.processInstanceTracker = processInstanceTracker;
    this.processDiagnosticsCollector = processDiagnosticsCollector;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    final List<ProcessDiagnosticsReport> reports =
        processInstanceTracker.trackedProcessInstanceIds().stream()
            .map(processDiagnosticsCollector::collect)
            .toList();
    HttpResponses.sendHtml(exchange, 200, renderPage(reports));
  }

  private static String renderPage(List<ProcessDiagnosticsReport> reports) {
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Flowable Test debug UI</title>
          %s
        </head>
        <body>
        %s
        <main class="flw-main">
          <div class="flw-page-header">
            <h1>Process instances</h1>
            <p class="flw-subtitle">Tracked for the currently running test method.</p>
          </div>
          <div class="flw-toolbar">
            <input id="flw-filter" type="search" placeholder="Filter by instance ID&hellip; (press /)"
                   oninput="flwFilterInstances(this.value)" %s>
          </div>
          %s
        </main>
        <script>
          function flwFilterInstances(query) {
            const needle = query.trim().toLowerCase();
            document.querySelectorAll('.flw-instance-group').forEach(function (group) {
              let anyVisible = false;
              group.querySelectorAll('li[data-id]').forEach(function (row) {
                const visible = row.dataset.id.toLowerCase().includes(needle);
                row.style.display = visible ? '' : 'none';
                anyVisible = anyVisible || visible;
              });
              group.style.display = anyVisible ? '' : 'none';
            });
          }
          document.addEventListener('keydown', function (event) {
            const typing = ['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName);
            if (!typing && event.key === '/') {
              event.preventDefault();
              document.getElementById('flw-filter').focus();
            } else if (!typing && event.key === 'r') {
              location.reload();
            }
          });
        </script>
        </body>
        </html>
        """
        .formatted(
            Layout.STYLE,
            Layout.topBar(reports.size() + " instance(s) tracked"),
            reports.isEmpty() ? "disabled" : "",
            renderGroups(reports));
  }

  private static String renderGroups(List<ProcessDiagnosticsReport> reports) {
    if (reports.isEmpty()) {
      return "<p class=\"flw-empty-state\">No process instances tracked for the current test "
          + "method yet.</p>";
    }
    final Map<String, List<ProcessDiagnosticsReport>> byDefinitionKey = new LinkedHashMap<>();
    for (final ProcessDiagnosticsReport report : reports) {
      byDefinitionKey
          .computeIfAbsent(
              Objects.requireNonNullElse(report.processDefinitionKey(), UNKNOWN_DEFINITION_LABEL),
              key -> new ArrayList<>())
          .add(report);
    }
    final StringBuilder groups = new StringBuilder();
    byDefinitionKey.forEach(
        (definitionKey, groupReports) -> groups.append(renderGroup(definitionKey, groupReports)));
    return groups.toString();
  }

  private static String renderGroup(String definitionKey, List<ProcessDiagnosticsReport> reports) {
    final StringBuilder rows = new StringBuilder();
    reports.forEach(report -> rows.append(renderRow(report)));
    return """
        <div class="flw-instance-group flw-card">
          <div class="flw-instance-group-header">
            <span class="flw-instance-group-name">%s</span>
            <span class="flw-instance-group-count">%d</span>
          </div>
          <ul class="flw-instance-list">%s</ul>
        </div>
        """
        .formatted(Html.escape(definitionKey), reports.size(), rows);
  }

  private static String renderRow(ProcessDiagnosticsReport report) {
    final String escapedId = Html.escape(report.processInstanceId());
    return """
        <li data-id="%s" class="flw-instance-row">
          <a href="/instances/%s" class="flw-instance-link">
            <span class="flw-badge %s">%s</span>
            <span class="flw-mono">%s</span>
          </a>
        </li>
        """
        .formatted(
            escapedId,
            escapedId,
            report.active() ? "flw-badge-active" : "flw-badge-ended",
            report.active() ? "Active" : "Ended",
            escapedId);
  }
}
