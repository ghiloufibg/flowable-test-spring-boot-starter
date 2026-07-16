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
 * ProcessDiagnosticsCollector#collect(String)} rather than re-querying the engine.
 *
 * <p><b>Alpine.js prototype</b> (see {@code claudedocs/bpmn-debug-ui-ux-enhancements-design.md},
 * "Frontend tooling"): the interactive chrome (tabs, toast, lightbox, refresh countdown/pause,
 * keyboard shortcuts) is driven by a vendored Alpine.js build ({@code
 * /static/alpine-3.15.12.min.js}) instead of hand-rolled DOM manipulation, evaluated side by side
 * against {@link InstanceListHandler}'s plain-vanilla-JS list page. All process data below is
 * still rendered server-side exactly as before -- only the interactivity layer changed.
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
        <body x-data="flwDebugPage()" x-init="init()">
        %s
        <main class="flw-main">
          <div class="flw-breadcrumb-row">
            <p class="flw-breadcrumb"><a href="/">&laquo; all instances</a></p>
            <button @click="copyDiagnostics('%s')">Copy diagnostics</button>
          </div>
          %s
          <div class="flw-tabs">
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'diagram' }" @click="activeTab = 'diagram'">Diagram</button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'variables' }" @click="activeTab = 'variables'">Variables<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'tasks' }" @click="activeTab = 'tasks'">Pending tasks<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'history' }" @click="activeTab = 'history'">Activity trail<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'failedjobs' }" @click="activeTab = 'failedjobs'">Failed jobs<span class="flw-tab-count">%d</span></button>
          </div>
          <div x-show="activeTab === 'diagram'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'variables'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'tasks'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'history'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'failedjobs'" x-transition class="flw-card">
            %s
          </div>
        </main>
        <div class="flw-lightbox" :class="{ 'flw-lightbox-open': lightboxSrc }" @click="closeLightbox()">
          <img :src="lightboxSrc" alt="BPMN diagram enlarged">
        </div>
        <div id="flw-toast" class="flw-toast" :class="{ 'flw-toast-visible': toastVisible }" x-text="toastMessage"></div>
        <script>
          function flwDebugPage() {
            const relativeTimeFormatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
            return {
              activeTab: sessionStorage.getItem('flw-active-tab') || 'diagram',
              paused: false,
              refreshRemaining: 3,
              toastMessage: '',
              toastVisible: false,
              toastTimeout: null,
              lightboxSrc: null,

              init() {
                const savedScroll = sessionStorage.getItem('flw-scroll');
                if (savedScroll) window.scrollTo(0, parseInt(savedScroll, 10));
                this.updateRelativeTimes();
                setInterval(() => {
                  this.updateRelativeTimes();
                  if (this.paused) return;
                  this.refreshRemaining--;
                  if (this.refreshRemaining <= 0) {
                    sessionStorage.setItem('flw-scroll', String(window.scrollY));
                    sessionStorage.setItem('flw-active-tab', this.activeTab);
                    location.reload();
                  }
                }, 1000);
                document.addEventListener('keydown', (event) => this.handleKeydown(event));
              },

              togglePause() { this.paused = !this.paused; },

              toast(message) {
                this.toastMessage = message;
                this.toastVisible = true;
                clearTimeout(this.toastTimeout);
                this.toastTimeout = setTimeout(() => { this.toastVisible = false; }, 1800);
              },

              copy(text) {
                navigator.clipboard.writeText(text).then(() => this.toast('Copied to clipboard'));
              },

              copyDiagnostics(processInstanceId) {
                fetch('/instances/' + processInstanceId + '/diagnostics.txt')
                  .then((response) => response.text())
                  .then((text) => {
                    navigator.clipboard.writeText(text);
                    this.toast('Diagnostics copied to clipboard');
                  })
                  .catch(() => this.toast('Failed to copy diagnostics'));
              },

              openLightbox(src) { this.lightboxSrc = src; },
              closeLightbox() { this.lightboxSrc = null; },

              diagramError(img) {
                const wrapper = img.parentElement;
                wrapper.innerHTML = '<p class="flw-empty-state">No BPMN diagram available for this '
                  + 'instance (missing graphical notation).</p>';
              },

              filterVariables(query) {
                const needle = query.trim().toLowerCase();
                document.querySelectorAll('#flw-variables-tbody tr[data-name]').forEach((row) => {
                  row.style.display = row.dataset.name.toLowerCase().includes(needle) ? '' : 'none';
                });
              },

              formatRelativeTime(isoTimestamp) {
                const elapsedSeconds = Math.round((new Date(isoTimestamp).getTime() - Date.now()) / 1000);
                if (Math.abs(elapsedSeconds) < 60) return relativeTimeFormatter.format(elapsedSeconds, 'second');
                const elapsedMinutes = Math.round(elapsedSeconds / 60);
                if (Math.abs(elapsedMinutes) < 60) return relativeTimeFormatter.format(elapsedMinutes, 'minute');
                const elapsedHours = Math.round(elapsedMinutes / 60);
                if (Math.abs(elapsedHours) < 24) return relativeTimeFormatter.format(elapsedHours, 'hour');
                return relativeTimeFormatter.format(Math.round(elapsedHours / 24), 'day');
              },

              updateRelativeTimes() {
                document.querySelectorAll('time[datetime]').forEach((el) => {
                  el.textContent = this.formatRelativeTime(el.getAttribute('datetime'));
                  el.title = el.getAttribute('datetime');
                });
              },

              handleKeydown(event) {
                if (event.key === 'Escape') {
                  this.closeLightbox();
                  return;
                }
                if (['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) return;
                if (event.key === '/') {
                  event.preventDefault();
                  this.activeTab = 'variables';
                  this.$nextTick(() => document.querySelector('#flw-tab-variables input[type="search"]')?.focus());
                } else if (event.key === 'r') {
                  location.reload();
                } else if (['1', '2', '3', '4', '5'].includes(event.key)) {
                  this.activeTab = ['diagram', 'variables', 'tasks', 'history', 'failedjobs'][Number(event.key) - 1];
                }
              },
            };
          }
        </script>
        <script defer src="/static/alpine-3.15.12.min.js"></script>
        </body>
        </html>
        """
        .formatted(
            escapedId,
            Layout.STYLE,
            Layout.topBar(refreshIndicatorMarkup()),
            escapedId,
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

  private static String refreshIndicatorMarkup() {
    return """
        <span class="flw-refresh-indicator">
          Refreshing in <strong x-text="refreshRemaining">3</strong>s
          <button @click="togglePause()" x-text="paused ? 'Resume' : 'Pause'">Pause</button>
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
              <div class="flw-meta-value"><code>%s</code><button @click="copy('%s')">Copy</button></div>
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
             @click="openLightbox($el.src)" @error="diagramError($el)">
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
          <input type="search" placeholder="Filter variables&hellip; (press /)" @input="filterVariables($event.target.value)">
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
          .append("</div><div class=\"flw-timeline-meta\"><time datetime=\"")
          .append(entry.startTime())
          .append("\">")
          .append(entry.startTime())
          .append("</time>")
          .append(
              running
                  ? " &mdash; still running"
                  : " &mdash; <time datetime=\"" + entry.endTime() + "\">" + entry.endTime() + "</time>")
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
