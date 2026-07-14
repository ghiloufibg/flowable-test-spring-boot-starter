package com.flowabletest.core.diagnostics;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityTrailEntry;
import java.time.Duration;
import java.util.List;

/** Renders {@link ProcessDiagnosticsReport}s into a human-readable plain-text block. */
public final class ProcessDiagnosticsFormatter {

  private ProcessDiagnosticsFormatter() {}

  /** Formats a batch of reports, one per tracked process instance, as a single text block. */
  public static String format(List<ProcessDiagnosticsReport> reports) {
    return format(reports, 0);
  }

  /**
   * Same as {@link #format(List)}, but appends a note when {@code omittedProcessInstanceCount} is
   * positive -- {@link ProcessInstanceTracker} stops tracking new process instances once its
   * configured limit is reached, so a report built from a capped list may not be the whole story.
   */
  public static String format(
      List<ProcessDiagnosticsReport> reports, int omittedProcessInstanceCount) {
    final StringBuilder text = new StringBuilder();
    text.append("===== Flowable process diagnostics =====\n");
    if (reports.isEmpty()) {
      text.append("(no process instances were tracked during this test)\n");
    } else {
      reports.forEach(report -> text.append(format(report)));
    }
    if (omittedProcessInstanceCount > 0) {
      text.append("... (")
          .append(omittedProcessInstanceCount)
          .append(" more process instance(s) not shown -- tracking limit reached)\n");
    }
    text.append("==========================================");
    return text.toString();
  }

  /** Formats a single process instance's snapshot. */
  public static String format(ProcessDiagnosticsReport report) {
    final StringBuilder text = new StringBuilder();
    text.append("Process instance ")
        .append(report.processInstanceId())
        .append(" -- ")
        .append(report.processDefinitionKey())
        .append(':')
        .append(report.processDefinitionVersion())
        .append(" -- businessKey=")
        .append(report.businessKey())
        .append(" -- ")
        .append(report.active() ? "ACTIVE" : "ENDED")
        .append('\n');

    appendCurrentActivities(text, report);
    appendVariables(text, report);
    appendActivityTrail(text, report);
    appendPendingTasks(text, report);
    appendFailedJobs(text, report);

    return text.toString();
  }

  private static void appendCurrentActivities(StringBuilder text, ProcessDiagnosticsReport report) {
    if (report.currentActivities().isEmpty()) {
      text.append("  Current activity: none\n");
      return;
    }
    report
        .currentActivities()
        .forEach(
            activity ->
                text.append("  Current activity: ")
                    .append(activity.activityType())
                    .append(" \"")
                    .append(activity.activityName())
                    .append("\" (id=")
                    .append(activity.activityId())
                    .append(")\n"));
  }

  private static void appendVariables(StringBuilder text, ProcessDiagnosticsReport report) {
    text.append("  Variables:\n");
    if (report.variables().isEmpty()) {
      text.append("    (none)\n");
      return;
    }
    report
        .variables()
        .forEach(
            (name, value) ->
                text.append("    ").append(name).append(" = ").append(value).append('\n'));
  }

  private static void appendActivityTrail(StringBuilder text, ProcessDiagnosticsReport report) {
    text.append("  Activity trail (last ").append(report.activityTrail().size()).append("):\n");
    if (report.activityTrail().isEmpty()) {
      text.append("    (none)\n");
      return;
    }
    report
        .activityTrail()
        .forEach(
            entry ->
                text.append("    ")
                    .append(entry.activityId())
                    .append(" \"")
                    .append(entry.activityName())
                    .append("\" ")
                    .append(formatDuration(entry))
                    .append('\n'));
  }

  private static void appendPendingTasks(StringBuilder text, ProcessDiagnosticsReport report) {
    text.append("  Pending tasks:\n");
    if (report.pendingTasks().isEmpty()) {
      text.append("    (none)\n");
      return;
    }
    report
        .pendingTasks()
        .forEach(
            task ->
                text.append("    - \"")
                    .append(task.name())
                    .append("\" id=")
                    .append(task.taskId())
                    .append(" assignee=")
                    .append(task.assignee())
                    .append(" candidateGroups=")
                    .append(task.candidateGroups())
                    .append('\n'));
  }

  private static void appendFailedJobs(StringBuilder text, ProcessDiagnosticsReport report) {
    if (report.failedJobs().isEmpty()) {
      text.append("  Failed jobs (dead letter): none\n");
      return;
    }
    text.append("  Failed jobs (dead letter):\n");
    report
        .failedJobs()
        .forEach(
            job ->
                text.append("    - jobId=")
                    .append(job.jobId())
                    .append(" elementId=")
                    .append(job.elementId())
                    .append(" retries=")
                    .append(job.retries())
                    .append(" exception=")
                    .append(job.exceptionMessage())
                    .append('\n')
                    .append(indent(job.exceptionStacktrace()))
                    .append('\n'));
  }

  private static String formatDuration(ActivityTrailEntry entry) {
    if (entry.endTime() == null) {
      return "(still active, started " + entry.startTime() + ")";
    }
    return Duration.between(entry.startTime(), entry.endTime()).toMillis() + "ms";
  }

  private static String indent(String stacktrace) {
    if (stacktrace == null || stacktrace.isBlank()) {
      return "      (no stacktrace recorded)";
    }
    return stacktrace
        .lines()
        .map(line -> "      " + line)
        .reduce((a, b) -> a + "\n" + b)
        .orElse("");
  }
}
