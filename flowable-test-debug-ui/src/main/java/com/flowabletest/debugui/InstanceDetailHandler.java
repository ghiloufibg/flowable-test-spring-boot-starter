package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityTrailEntry;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.FailedJobInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.PendingTaskInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /instances/{id}} -- diagram, variables, current activities, pending tasks, activity
 * trail, and failed jobs for one process instance, all sourced from {@link
 * ProcessDiagnosticsCollector#collect(String)} rather than re-querying the engine. The page
 * refreshes itself every few seconds via JavaScript (not a bare {@code <meta refresh>}) so it can
 * preserve scroll position and the selected tab across reloads, since this is a live view, not a
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
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Flowable Test debug UI - %s</title>
          %s
        </head>
        <body>
        %s
        <main class="flw-main">
          <p class="flw-breadcrumb"><a href="/">&laquo; all instances</a></p>
          %s
          <div class="flw-tabs">
            <button id="flw-tabbtn-diagram" class="flw-tab-btn flw-tab-btn-active" onclick="flwShowTab('diagram')">Diagram</button>
            <button id="flw-tabbtn-variables" class="flw-tab-btn" onclick="flwShowTab('variables')">Variables<span class="flw-tab-count">%d</span></button>
            <button id="flw-tabbtn-tasks" class="flw-tab-btn" onclick="flwShowTab('tasks')">Pending tasks<span class="flw-tab-count">%d</span></button>
            <button id="flw-tabbtn-history" class="flw-tab-btn" onclick="flwShowTab('history')">Activity trail<span class="flw-tab-count">%d</span></button>
            <button id="flw-tabbtn-failedjobs" class="flw-tab-btn" onclick="flwShowTab('failedjobs')">Failed jobs<span class="flw-tab-count">%d</span></button>
          </div>
          <div id="flw-tab-diagram" class="flw-tab-panel flw-tab-active flw-card">
            %s
          </div>
          <div id="flw-tab-variables" class="flw-tab-panel flw-card">
            %s
          </div>
          <div id="flw-tab-tasks" class="flw-tab-panel flw-card">
            %s
          </div>
          <div id="flw-tab-history" class="flw-tab-panel flw-card">
            %s
          </div>
          <div id="flw-tab-failedjobs" class="flw-tab-panel flw-card">
            %s
          </div>
        </main>
        <div id="flw-lightbox" class="flw-lightbox" onclick="flwCloseLightbox()">
          <img id="flw-lightbox-img" alt="BPMN diagram enlarged">
        </div>
        <script>
          let flwActiveTab = 'diagram';
          let flwPaused = false;
          let flwRefreshRemaining = 3;

          function flwShowTab(name) {
            flwActiveTab = name;
            document.querySelectorAll('.flw-tab-panel').forEach(function (p) { p.classList.remove('flw-tab-active'); });
            document.querySelectorAll('.flw-tab-btn').forEach(function (b) { b.classList.remove('flw-tab-btn-active'); });
            document.getElementById('flw-tab-' + name).classList.add('flw-tab-active');
            document.getElementById('flw-tabbtn-' + name).classList.add('flw-tab-btn-active');
          }

          function flwCopy(text, btn) {
            navigator.clipboard.writeText(text).then(function () {
              const original = btn.textContent;
              btn.textContent = 'Copied!';
              setTimeout(function () { btn.textContent = original; }, 1200);
            });
          }

          function flwOpenLightbox(src) {
            document.getElementById('flw-lightbox-img').src = src;
            document.getElementById('flw-lightbox').classList.add('flw-lightbox-open');
          }

          function flwCloseLightbox() {
            document.getElementById('flw-lightbox').classList.remove('flw-lightbox-open');
          }

          function flwDiagramError(img) {
            const wrapper = img.parentElement;
            wrapper.innerHTML = '<p class="flw-empty-state">No BPMN diagram available for this '
              + 'instance (missing graphical notation).</p>';
          }

          function flwFilterVariables(query) {
            const needle = query.trim().toLowerCase();
            document.querySelectorAll('#flw-variables-tbody tr[data-name]').forEach(function (row) {
              row.style.display = row.dataset.name.toLowerCase().includes(needle) ? '' : 'none';
            });
          }

          function flwTogglePause(btn) {
            flwPaused = !flwPaused;
            btn.textContent = flwPaused ? 'Resume' : 'Pause';
          }

          setInterval(function () {
            if (flwPaused) return;
            flwRefreshRemaining--;
            const el = document.getElementById('flw-refresh-countdown');
            if (el) el.textContent = flwRefreshRemaining;
            if (flwRefreshRemaining <= 0) {
              sessionStorage.setItem('flw-scroll', String(window.scrollY));
              sessionStorage.setItem('flw-active-tab', flwActiveTab);
              location.reload();
            }
          }, 1000);

          window.addEventListener('DOMContentLoaded', function () {
            const savedTab = sessionStorage.getItem('flw-active-tab');
            if (savedTab) flwShowTab(savedTab);
            const savedScroll = sessionStorage.getItem('flw-scroll');
            if (savedScroll) window.scrollTo(0, parseInt(savedScroll, 10));
          });
        </script>
        </body>
        </html>
        """
        .formatted(
            escapedId,
            Layout.STYLE,
            Layout.topBar(refreshIndicator()),
            renderHeader(escapedId, report),
            report.variables().size(),
            report.pendingTasks().size(),
            report.activityTrail().size(),
            report.failedJobs().size(),
            renderDiagram(escapedId),
            renderVariables(report.variables()),
            renderTasks(report.pendingTasks()),
            renderActivityTrail(report.activityTrail()),
            renderFailedJobs(report.failedJobs()));
  }

  private static String refreshIndicator() {
    return """
        <span class="flw-refresh-indicator">
          Refreshing in <strong id="flw-refresh-countdown">3</strong>s
          <button onclick="flwTogglePause(this)">Pause</button>
        </span>
        """;
  }

  private static String renderHeader(String escapedId, ProcessDiagnosticsReport report) {
    return """
        <div class="flw-card flw-detail-header">
          <div class="flw-detail-title">
            <span class="flw-badge %s">%s</span>
            <h1>%s <span class="flw-version">v%s</span></h1>
          </div>
          <div class="flw-meta-row">
            <div>
              <span class="flw-meta-label">Instance ID</span>
              <div class="flw-meta-value"><code>%s</code><button onclick="flwCopy('%s', this)">Copy</button></div>
            </div>
            <div>
              <span class="flw-meta-label">Business key</span>
              <div class="flw-meta-value">%s</div>
            </div>
          </div>
          %s
        </div>
        """
        .formatted(
            report.active() ? "flw-badge-active" : "flw-badge-ended",
            report.active() ? "&#9679; Active" : "Ended",
            Html.escape(report.processDefinitionKey()),
            report.processDefinitionVersion(),
            escapedId,
            escapedId,
            report.businessKey() != null ? Html.escape(report.businessKey()) : "&mdash;",
            renderCurrentActivities(report.currentActivities()));
  }

  private static String renderCurrentActivities(List<ActivityInfo> activities) {
    if (activities.isEmpty()) {
      return "";
    }
    final StringBuilder badges = new StringBuilder("<div class=\"flw-current-activities\">");
    for (final ActivityInfo activity : activities) {
      badges
          .append("<span class=\"flw-badge flw-badge-neutral\">&#9654; ")
          .append(Html.escape(activity.activityName() != null ? activity.activityName() : activity.activityId()))
          .append("</span>");
    }
    return badges.append("</div>").toString();
  }

  private static String renderDiagram(String escapedId) {
    return """
        <img src="/instances/%s/diagram.png" alt="BPMN diagram" class="flw-diagram-img"
             onclick="flwOpenLightbox(this.src)" onerror="flwDiagramError(this)">
        <p class="flw-diagram-hint">Click the diagram to enlarge. The current activity is highlighted.</p>
        """
        .formatted(escapedId);
  }

  private static String renderVariables(Map<String, String> variables) {
    if (variables.isEmpty()) {
      return "<p class=\"flw-empty-state\">No process variables.</p>";
    }
    final StringBuilder rows = new StringBuilder();
    variables.forEach(
        (name, value) ->
            rows.append("<tr data-name=\"")
                .append(Html.escape(name))
                .append("\"><td class=\"flw-mono\">")
                .append(Html.escape(name))
                .append("</td><td class=\"flw-mono\">")
                .append(Html.escape(value))
                .append("</td></tr>"));
    return """
        <div class="flw-toolbar" style="padding: 16px 16px 0;">
          <input type="search" placeholder="Filter variables&hellip;" oninput="flwFilterVariables(this.value)">
        </div>
        <table>
          <thead><tr><th>Name</th><th>Value</th></tr></thead>
          <tbody id="flw-variables-tbody">%s</tbody>
        </table>
        """
        .formatted(rows);
  }

  private static String renderTasks(List<PendingTaskInfo> tasks) {
    if (tasks.isEmpty()) {
      return "<p class=\"flw-empty-state\">No pending user tasks.</p>";
    }
    final StringBuilder items = new StringBuilder();
    for (final PendingTaskInfo task : tasks) {
      final String candidateGroups = String.join(", ", task.candidateGroups());
      items
          .append("<div class=\"flw-task-card\"><div class=\"flw-task-name\">")
          .append(Html.escape(task.name()))
          .append("</div><span class=\"flw-badge flw-badge-neutral\">assignee: ")
          .append(task.assignee() != null ? Html.escape(task.assignee()) : "unassigned")
          .append("</span> ")
          .append(
              candidateGroups.isEmpty()
                  ? ""
                  : "<span class=\"flw-badge flw-badge-neutral\">groups: "
                      + Html.escape(candidateGroups)
                      + "</span>")
          .append("</div>");
    }
    return items.toString();
  }

  private static String renderActivityTrail(List<ActivityTrailEntry> trail) {
    if (trail.isEmpty()) {
      return "<p class=\"flw-empty-state\">No activity trail recorded yet.</p>";
    }
    final StringBuilder items = new StringBuilder("<ol class=\"flw-timeline\">");
    for (final ActivityTrailEntry entry : trail) {
      final boolean running = entry.endTime() == null;
      items
          .append("<li class=\"flw-timeline-item\"><span class=\"flw-timeline-dot")
          .append(running ? " flw-timeline-dot-running" : "")
          .append("\"></span><div class=\"flw-timeline-name\">")
          .append(Html.escape(entry.activityName() != null ? entry.activityName() : entry.activityId()))
          .append("</div><div class=\"flw-timeline-meta\">")
          .append(entry.startTime())
          .append(running ? " &mdash; still running" : " &mdash; " + entry.endTime())
          .append(" (")
          .append(formatDuration(entry.startTime(), entry.endTime()))
          .append(")</div></li>");
    }
    return items.append("</ol>").toString();
  }

  private static String renderFailedJobs(List<FailedJobInfo> failedJobs) {
    if (failedJobs.isEmpty()) {
      return "<p class=\"flw-empty-state\">No failed jobs.</p>";
    }
    final StringBuilder items = new StringBuilder();
    for (final FailedJobInfo job : failedJobs) {
      items
          .append("<div class=\"flw-failedjob-card\"><div class=\"flw-failedjob-header\">")
          .append("<span class=\"flw-mono\">")
          .append(Html.escape(job.elementId()))
          .append("</span><span class=\"flw-badge flw-badge-error\">")
          .append(job.retries())
          .append(" retries left</span></div><div class=\"flw-failedjob-message\">")
          .append(Html.escape(job.exceptionMessage()))
          .append("</div>");
      if (job.exceptionStacktrace() != null && !job.exceptionStacktrace().isBlank()) {
        items
            .append("<details><summary>Stack trace</summary><pre>")
            .append(Html.escape(job.exceptionStacktrace()))
            .append("</pre></details>");
      }
      items.append("</div>");
    }
    return items.toString();
  }

  private static String formatDuration(Instant start, Instant end) {
    if (start == null) {
      return "";
    }
    final Duration duration = Duration.between(start, end != null ? end : Instant.now());
    final long totalSeconds = Math.max(duration.getSeconds(), 0);
    if (totalSeconds < 60) {
      return totalSeconds + "s";
    }
    final long minutes = totalSeconds / 60;
    final long seconds = totalSeconds % 60;
    if (minutes < 60) {
      return minutes + "m " + seconds + "s";
    }
    final long hours = minutes / 60;
    final long remainingMinutes = minutes % 60;
    return hours + "h " + remainingMinutes + "m";
  }
}
