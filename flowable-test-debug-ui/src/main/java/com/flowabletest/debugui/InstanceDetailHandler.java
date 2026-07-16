package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityTrailEntry;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.CompletedTaskInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.FailedJobInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.GatewayOutgoingFlow;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.GatewayTraceEntry;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.IdentityLinkInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.MultiInstanceProgress;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.PendingJobInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.PendingTaskInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.VariableHistoryEntry;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /instances/{id}} -- process definition metadata, diagram, variables and their history,
 * pending and completed tasks, activity trail, gateway trace, and both failed and pending job state
 * for one process instance, all sourced from {@link ProcessDiagnosticsCollector#collect(String)}
 * rather than re-querying the engine directly. Parent/spawned-instance links are the one piece of
 * data that needs more than a single {@code collect()} call: {@link
 * ProcessInstanceTracker#trackedProcessInstanceIds()} is scanned and each tracked instance's own
 * report consulted for a matching {@code superProcessInstanceId} (see {@code
 * claudedocs/bpmn-debug-ui-ux-enhancements-design.md}, Tier 3 item 11).
 *
 * <p><b>Alpine.js prototype</b> (see the same design doc, "Frontend tooling"): the interactive
 * chrome (tabs, toast, lightbox, refresh countdown/pause, keyboard shortcuts, the
 * diagram/XML-source toggle) is driven by a vendored Alpine.js build ({@code
 * /static/alpine-3.15.12.min.js}) instead of hand-rolled DOM manipulation, evaluated side by side
 * against {@link InstanceListHandler}'s plain-vanilla-JS list page. All process data below is still
 * rendered server-side exactly as before -- only the interactivity layer changed.
 */
final class InstanceDetailHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/instances/";

  private final ProcessDiagnosticsCollector processDiagnosticsCollector;
  private final ProcessInstanceTracker processInstanceTracker;

  InstanceDetailHandler(
      ProcessDiagnosticsCollector processDiagnosticsCollector,
      ProcessInstanceTracker processInstanceTracker) {
    this.processDiagnosticsCollector = processDiagnosticsCollector;
    this.processInstanceTracker = processInstanceTracker;
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
    final List<String> childInstanceIds = childInstanceIds(processInstanceId);
    HttpResponses.sendHtml(exchange, 200, renderPage(processInstanceId, report, childInstanceIds));
  }

  /**
   * Every other tracked instance (started during the same test method, see {@link
   * ProcessInstanceTracker}) whose {@code superProcessInstanceId} points back at {@code
   * processInstanceId} -- i.e. every call-activity-spawned child. One {@code collect()} call per
   * tracked instance; accepted at the scale a single test method tracks (see the design doc's
   * "Risks" section).
   */
  private List<String> childInstanceIds(String processInstanceId) {
    return processInstanceTracker.trackedProcessInstanceIds().stream()
        .filter(id -> !id.equals(processInstanceId))
        .filter(
            id ->
                processInstanceId.equals(
                    processDiagnosticsCollector.collect(id).superProcessInstanceId()))
        .toList();
  }

  private static String renderPage(
      String processInstanceId, ProcessDiagnosticsReport report, List<String> childInstanceIds) {
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
            <button @click="copyAssertionSnippet('%s')">Copy as assertion</button>
          </div>
          %s
          <div class="flw-tabs">
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'diagram' }" @click="activeTab = 'diagram'">Diagram</button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'variables' }" @click="activeTab = 'variables'">Variables<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'variablehistory' }" @click="activeTab = 'variablehistory'">Variable history<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'tasks' }" @click="activeTab = 'tasks'">Pending tasks<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'completed' }" @click="activeTab = 'completed'">Completed tasks<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'history' }" @click="activeTab = 'history'">Activity trail<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'gatewaytrace' }" @click="activeTab = 'gatewaytrace'">Gateway trace<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'failedjobs' }" @click="activeTab = 'failedjobs'">Failed jobs<span class="flw-tab-count">%d</span></button>
            <button class="flw-tab-btn" :class="{ 'flw-tab-btn-active': activeTab === 'pendingjobs' }" @click="activeTab = 'pendingjobs'">Pending jobs<span class="flw-tab-count">%d</span></button>
          </div>
          <div x-show="activeTab === 'diagram'" x-transition class="flw-card flw-diagram-card">
            %s
          </div>
          <div x-show="activeTab === 'variables'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'variablehistory'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'tasks'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'completed'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'history'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'gatewaytrace'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'failedjobs'" x-transition class="flw-card">
            %s
          </div>
          <div x-show="activeTab === 'pendingjobs'" x-transition class="flw-card">
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
            const TAB_IDS = ['diagram', 'variables', 'variablehistory', 'tasks', 'completed', 'history', 'gatewaytrace', 'failedjobs', 'pendingjobs'];
            const initialTab = () => {
              const fromQuery = new URLSearchParams(location.search).get('tab');
              if (TAB_IDS.includes(fromQuery)) return fromQuery;
              const fromSession = sessionStorage.getItem('flw-active-tab');
              if (TAB_IDS.includes(fromSession)) return fromSession;
              return 'diagram';
            };
            return {
              activeTab: initialTab(),
              paused: false,
              refreshRemaining: 3,
              toastMessage: '',
              toastVisible: false,
              toastTimeout: null,
              lightboxSrc: null,
              showDefinitionSource: false,
              definitionXml: null,
              diagramFailed: false,

              init() {
                const savedScroll = sessionStorage.getItem('flw-scroll');
                if (savedScroll) window.scrollTo(0, parseInt(savedScroll, 10));
                this.updateRelativeTimes();
                this.$watch('activeTab', (value) => {
                  sessionStorage.setItem('flw-active-tab', value);
                  const url = new URL(location.href);
                  url.searchParams.set('tab', value);
                  history.replaceState(null, '', url);
                });
                setInterval(() => {
                  this.updateRelativeTimes();
                  if (this.paused) return;
                  this.refreshRemaining--;
                  if (this.refreshRemaining <= 0) {
                    sessionStorage.setItem('flw-scroll', String(window.scrollY));
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

              copyAssertionSnippet(processInstanceId) {
                fetch('/instances/' + processInstanceId + '/assertion.txt')
                  .then((response) => response.text())
                  .then((text) => {
                    navigator.clipboard.writeText(text);
                    this.toast('Assertion snippet copied to clipboard');
                  })
                  .catch(() => this.toast('Failed to copy assertion snippet'));
              },

              toggleDefinitionSource(processInstanceId) {
                this.showDefinitionSource = !this.showDefinitionSource;
                if (this.showDefinitionSource && this.definitionXml === null) {
                  fetch('/instances/' + processInstanceId + '/definition.xml')
                    .then((response) => response.text())
                    .then((text) => { this.definitionXml = text; })
                    .catch(() => { this.definitionXml = 'Failed to load BPMN source.'; });
                }
              },

              openLightbox(src) { this.lightboxSrc = src; },
              closeLightbox() { this.lightboxSrc = null; },

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
                } else if (['1', '2', '3', '4', '5', '6', '7', '8', '9'].includes(event.key)) {
                  this.activeTab = TAB_IDS[Number(event.key) - 1];
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
            escapedId,
            renderHeader(escapedId, report, childInstanceIds),
            report.variables().size(),
            report.variableHistory().size(),
            report.pendingTasks().size(),
            report.completedTasks().size(),
            report.activityTrail().size(),
            report.gatewayTrace().size(),
            report.failedJobs().size(),
            report.pendingJobs().size(),
            renderDiagram(escapedId),
            renderVariables(report.variables()),
            renderVariableHistory(report.variableHistory()),
            renderTasks(report.pendingTasks()),
            renderCompletedTasks(report.completedTasks()),
            renderActivityTrail(report.activityTrail()),
            renderGatewayTrace(report.gatewayTrace()),
            renderFailedJobs(report.failedJobs()),
            renderPendingJobs(report.pendingJobs()));
  }

  private static String refreshIndicatorMarkup() {
    return """
        <span class="flw-refresh-indicator">
          Refreshing in <strong x-text="refreshRemaining">3</strong>s
          <button @click="togglePause()" x-text="paused ? 'Resume' : 'Pause'">Pause</button>
        </span>
        """;
  }

  private static String renderHeader(
      String escapedId, ProcessDiagnosticsReport report, List<String> childInstanceIds) {
    return """
        <div class="flw-card flw-detail-header">
          <div class="flw-detail-title">
            <span class="flw-badge %s">%s</span>
            <h1>%s <span class="flw-version">v%s</span></h1>
            %s
          </div>
          %s
          <div class="flw-meta-row">
            <div>
              <span class="flw-meta-label">Instance ID</span>
              <div class="flw-meta-value"><code>%s</code><button @click="copy('%s')">Copy</button></div>
            </div>
            <div>
              <span class="flw-meta-label">Business key</span>
              <div class="flw-meta-value">%s</div>
            </div>
            <div>
              <span class="flw-meta-label">Deployed</span>
              <div class="flw-meta-value">%s</div>
            </div>
            <div>
              <span class="flw-meta-label">Started by</span>
              <div class="flw-meta-value">%s</div>
            </div>
          </div>
          %s
          %s
        </div>
        """
        .formatted(
            report.active() ? "flw-badge-active" : "flw-badge-ended",
            report.active() ? "&#9679; Active" : "Ended",
            Html.escape(report.processDefinitionKey()),
            report.processDefinitionVersion(),
            renderFailedJobBanner(report.failedJobs()),
            renderDefinitionSubtitle(report),
            escapedId,
            escapedId,
            report.businessKey() != null ? Html.escape(report.businessKey()) : "&mdash;",
            renderTimeOrDash(report.deploymentTime()),
            report.testOrigin() != null ? Html.escape(report.testOrigin()) : "&mdash;",
            renderLineage(report, childInstanceIds),
            renderCurrentActivities(report.currentActivities()));
  }

  private static String renderFailedJobBanner(List<FailedJobInfo> failedJobs) {
    if (failedJobs.isEmpty()) {
      return "";
    }
    return """
        <button class="flw-badge flw-badge-error flw-failedjob-banner" @click="activeTab = 'failedjobs'">
          &#9888; %d failed job(s)
        </button>
        """
        .formatted(failedJobs.size());
  }

  private static String renderDefinitionSubtitle(ProcessDiagnosticsReport report) {
    if (report.processDefinitionName() == null && report.processDefinitionCategory() == null) {
      return "";
    }
    final StringBuilder subtitle = new StringBuilder("<p class=\"flw-detail-subtitle\">");
    if (report.processDefinitionName() != null) {
      subtitle.append(Html.escape(report.processDefinitionName()));
    }
    if (report.processDefinitionCategory() != null) {
      if (report.processDefinitionName() != null) {
        subtitle.append(" &middot; ");
      }
      subtitle.append("category: ").append(Html.escape(report.processDefinitionCategory()));
    }
    return subtitle.append("</p>").toString();
  }

  private static String renderLineage(
      ProcessDiagnosticsReport report, List<String> childInstanceIds) {
    if (report.superProcessInstanceId() == null && childInstanceIds.isEmpty()) {
      return "";
    }
    final StringBuilder lineage = new StringBuilder("<div class=\"flw-lineage\">");
    if (report.superProcessInstanceId() != null) {
      lineage
          .append("<div class=\"flw-lineage-row\">Part of instance: ")
          .append(renderInstanceLink(report.superProcessInstanceId()))
          .append("</div>");
    }
    if (!childInstanceIds.isEmpty()) {
      lineage.append("<div class=\"flw-lineage-row\">Spawned instances: ");
      for (int i = 0; i < childInstanceIds.size(); i++) {
        if (i > 0) {
          lineage.append(", ");
        }
        lineage.append(renderInstanceLink(childInstanceIds.get(i)));
      }
      lineage.append("</div>");
    }
    return lineage.append("</div>").toString();
  }

  private static String renderInstanceLink(String processInstanceId) {
    final String escaped = Html.escape(processInstanceId);
    return "<a href=\"/instances/" + escaped + "\" class=\"flw-mono\">" + escaped + "</a>";
  }

  private static String renderTimeOrDash(Instant instant) {
    if (instant == null) {
      return "&mdash;";
    }
    return "<time datetime=\"" + instant + "\">" + instant + "</time>";
  }

  private static String renderCurrentActivities(List<ActivityInfo> activities) {
    if (activities.isEmpty()) {
      return "";
    }
    final StringBuilder badges = new StringBuilder("<div class=\"flw-current-activities\">");
    for (final ActivityInfo activity : activities) {
      badges
          .append("<span class=\"flw-badge flw-badge-neutral\">&#9654; ")
          .append(
              Html.escape(
                  activity.activityName() != null
                      ? activity.activityName()
                      : activity.activityId()));
      final MultiInstanceProgress progress = activity.multiInstanceProgress();
      if (progress != null) {
        badges
            .append(" (")
            .append(progress.nrOfCompletedInstances())
            .append('/')
            .append(progress.nrOfInstances())
            .append(" complete)");
      }
      badges.append("</span>");
      if (activity.calledProcessInstanceId() != null) {
        badges.append(' ').append(renderInstanceLink(activity.calledProcessInstanceId()));
      }
    }
    return badges.append("</div>").toString();
  }

  private static String renderDiagram(String escapedId) {
    return """
        <img src="/instances/%s/diagram.png" alt="BPMN diagram" class="flw-diagram-img"
             x-show="!showDefinitionSource && !diagramFailed"
             @click="openLightbox($el.src)" @error="diagramFailed = true">
        <p x-show="diagramFailed && !showDefinitionSource" class="flw-empty-state">
          No BPMN diagram available for this instance (missing graphical notation).
        </p>
        <pre x-show="showDefinitionSource" x-text="definitionXml || 'Loading…'" class="flw-definition-source"></pre>
        <p class="flw-diagram-hint">
          <span x-show="!showDefinitionSource">Click the diagram to enlarge. The current activity is highlighted.</span>
          <button @click="toggleDefinitionSource('%s')" x-text="showDefinitionSource ? 'View diagram' : 'View BPMN XML source'"></button>
        </p>
        """
        .formatted(escapedId, escapedId);
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

  private static String renderVariableHistory(List<VariableHistoryEntry> history) {
    if (history.isEmpty()) {
      return "<p class=\"flw-empty-state\">No variable history recorded yet. This requires the "
          + "engine's <code>flowable.history-level</code> to be <code>full</code>.</p>";
    }
    final StringBuilder rows = new StringBuilder();
    for (int i = history.size() - 1; i >= 0; i--) {
      final VariableHistoryEntry entry = history.get(i);
      rows.append("<tr><td class=\"flw-mono\">")
          .append(renderTimeOrDash(entry.time()))
          .append("</td><td class=\"flw-mono\">")
          .append(Html.escape(entry.variableName()))
          .append("</td><td class=\"flw-mono\">")
          .append(Html.escape(entry.value()))
          .append("</td><td class=\"flw-mono\">")
          .append(entry.revision())
          .append("</td></tr>");
    }
    return """
        <table>
          <thead><tr><th>Time</th><th>Variable</th><th>Value</th><th>Revision</th></tr></thead>
          <tbody>%s</tbody>
        </table>
        """
        .formatted(rows);
  }

  private static String renderGatewayTrace(List<GatewayTraceEntry> trace) {
    if (trace.isEmpty()) {
      return "<p class=\"flw-empty-state\">No gateway transitions recorded yet.</p>";
    }
    final StringBuilder items = new StringBuilder();
    for (final GatewayTraceEntry gateway : trace) {
      items
          .append("<div class=\"flw-gateway-card\"><div class=\"flw-gateway-header\">")
          .append(
              Html.escape(
                  gateway.gatewayName() != null ? gateway.gatewayName() : gateway.gatewayId()))
          .append(" <span class=\"flw-badge flw-badge-neutral\">")
          .append(Html.escape(gateway.gatewayType()))
          .append("</span> ")
          .append(renderTimeOrDash(gateway.time()))
          .append("</div><ul class=\"flw-gateway-flows\">");
      for (final GatewayOutgoingFlow flow : gateway.outgoingFlows()) {
        items
            .append("<li class=\"")
            .append(flow.taken() ? "flw-gateway-flow-taken" : "flw-gateway-flow-not-taken")
            .append("\">")
            .append(flow.taken() ? "&#10003; taken &rarr; " : "&#10005; not taken &rarr; ")
            .append(Html.escape(flow.targetActivityName()));
        if (flow.conditionExpression() != null && !flow.conditionExpression().isBlank()) {
          items
              .append(" <code class=\"flw-mono\">")
              .append(Html.escape(flow.conditionExpression()))
              .append("</code>");
        }
        items.append("</li>");
      }
      items.append("</ul></div>");
    }
    return items.toString();
  }

  private static String renderPendingJobs(List<PendingJobInfo> jobs) {
    if (jobs.isEmpty()) {
      return "<p class=\"flw-empty-state\">No pending jobs.</p>";
    }
    final Instant now = Instant.now();
    final StringBuilder items = new StringBuilder();
    for (final PendingJobInfo job : jobs) {
      final boolean overdue = job.dueDate() != null && job.dueDate().isBefore(now);
      items
          .append("<div class=\"flw-task-card\"><div class=\"flw-task-name\">")
          .append(Html.escape(job.elementName() != null ? job.elementName() : job.elementId()))
          .append("</div><span class=\"flw-badge flw-badge-neutral\">")
          .append(Html.escape(job.jobType()))
          .append("</span> <span class=\"flw-badge flw-badge-neutral\">retries: ")
          .append(job.retries())
          .append("</span> ")
          .append(renderDueDateBadge(job.dueDate(), overdue))
          .append("</div>");
    }
    return items.toString();
  }

  private static String renderTasks(List<PendingTaskInfo> tasks) {
    if (tasks.isEmpty()) {
      return "<p class=\"flw-empty-state\">No pending user tasks.</p>";
    }
    final Instant now = Instant.now();
    final StringBuilder items = new StringBuilder();
    for (final PendingTaskInfo task : tasks) {
      final String candidateGroups = String.join(", ", task.candidateGroups());
      final boolean overdue = task.dueDate() != null && task.dueDate().isBefore(now);
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
                      + "</span> ")
          .append("<span class=\"flw-badge flw-badge-neutral\">priority: ")
          .append(task.priority())
          .append("</span> ")
          .append(renderDueDateBadge(task.dueDate(), overdue))
          .append(renderOtherIdentityLinks(task.identityLinks()))
          .append("</div>");
    }
    return items.toString();
  }

  private static String renderDueDateBadge(Instant dueDate, boolean overdue) {
    if (dueDate == null) {
      return "";
    }
    return (overdue
            ? "<span class=\"flw-badge flw-badge-error\">overdue, due "
            : "<span class=\"flw-badge flw-badge-neutral\">due ")
        + "<time datetime=\""
        + dueDate
        + "\">"
        + dueDate
        + "</time></span> ";
  }

  private static String renderOtherIdentityLinks(List<IdentityLinkInfo> identityLinks) {
    final StringBuilder badges = new StringBuilder();
    for (final IdentityLinkInfo link : identityLinks) {
      // Assignee and candidate-group links already have their own dedicated badges above --
      // everything else (candidate *users*, owner, participant, starter, ...) shows up here.
      final boolean alreadyShown =
          "assignee".equals(link.type())
              || ("candidate".equals(link.type()) && link.groupId() != null);
      if (alreadyShown) {
        continue;
      }
      final String who = link.userId() != null ? link.userId() : link.groupId();
      if (who == null) {
        continue;
      }
      badges
          .append("<span class=\"flw-badge flw-badge-neutral\">")
          .append(Html.escape(link.type()))
          .append(": ")
          .append(Html.escape(who))
          .append("</span> ");
    }
    return badges.toString();
  }

  private static String renderCompletedTasks(List<CompletedTaskInfo> completedTasks) {
    if (completedTasks.isEmpty()) {
      return "<p class=\"flw-empty-state\">No completed tasks yet.</p>";
    }
    final StringBuilder items = new StringBuilder();
    for (final CompletedTaskInfo task : completedTasks) {
      items
          .append("<div class=\"flw-task-card\"><div class=\"flw-task-name\">")
          .append(Html.escape(task.name()))
          .append("</div><span class=\"flw-badge flw-badge-neutral\">assignee: ")
          .append(task.assignee() != null ? Html.escape(task.assignee()) : "unassigned")
          .append("</span> <span class=\"flw-badge flw-badge-neutral\">duration: ")
          .append(formatMillis(task.durationMillis()))
          .append("</span> ")
          .append(
              task.deleteReason() != null
                  ? "<span class=\"flw-badge flw-badge-error\">deleted: "
                      + Html.escape(task.deleteReason())
                      + "</span>"
                  : "<span class=\"flw-badge flw-badge-active\">completed</span>")
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
          .append(
              Html.escape(entry.activityName() != null ? entry.activityName() : entry.activityId()))
          .append("</div><div class=\"flw-timeline-meta\"><time datetime=\"")
          .append(entry.startTime())
          .append("\">")
          .append(entry.startTime())
          .append("</time>")
          .append(
              running
                  ? " &mdash; still running"
                  : " &mdash; <time datetime=\""
                      + entry.endTime()
                      + "\">"
                      + entry.endTime()
                      + "</time>")
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
          .append(
              job.exceptionMessage() != null && !job.exceptionMessage().isBlank()
                  ? Html.escape(job.exceptionMessage())
                  : "(no exception message)")
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
    return formatMillis(Math.max(duration.toMillis(), 0));
  }

  private static String formatMillis(Long millis) {
    if (millis == null) {
      return "?";
    }
    final long totalSeconds = millis / 1000;
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
