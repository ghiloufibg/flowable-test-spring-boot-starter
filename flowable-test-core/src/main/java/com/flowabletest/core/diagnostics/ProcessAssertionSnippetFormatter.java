package com.flowabletest.core.diagnostics;

import com.flowabletest.core.assertions.ProcessInstanceAssert;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityInfo;
import java.util.stream.Collectors;

/**
 * Renders a {@link ProcessDiagnosticsReport} as a ready-to-paste {@code ProcessInstanceAssert}
 * snippet, so confirming "this is the state I expect" while looking at a process instance in the
 * debug UI can become working test code in one copy-paste instead of hand-typing the assertion
 * after reading the UI.
 */
public final class ProcessAssertionSnippetFormatter {

  private static final String REDACTED_PLACEHOLDER = "[REDACTED]";
  private static final String TRUNCATED_MARKER = "... (truncated, ";

  private ProcessAssertionSnippetFormatter() {}

  /** Formats a snippet for the given report. Always ends with a terminating {@code ;}. */
  public static String format(ProcessDiagnosticsReport report) {
    final StringBuilder variableAssertions = new StringBuilder();
    appendVariableAssertions(variableAssertions, report);

    final StringBuilder snippet = new StringBuilder();
    if (!variableAssertions.isEmpty()) {
      snippet.append(
          "// variable values below are copied as Strings -- adjust types (int/boolean/etc.) to"
              + " match your process variables\n");
    }
    snippet
        .append("processTestHarness.assertThat(")
        .append(quote(report.processInstanceId()))
        .append(")\n");
    appendLifecycleAssertion(snippet, report);
    snippet.append(variableAssertions);
    snippet.append("    ;\n");
    return snippet.toString();
  }

  /**
   * {@code isWaitingAt} is only emitted while active -- {@link ProcessInstanceAssert} itself
   * requires it (calls {@link ProcessInstanceAssert#isActive()} first), and this is a safe 1:1
   * mapping since {@link ProcessDiagnosticsCollector}'s current-activities query and {@code
   * ProcessInstanceAssert}'s own resolution use the identical shape ({@code unfinished()},
   * sequence-flow entries excluded).
   *
   * <p>{@code hasEndedAt} instead falls back to the last {@link
   * ProcessDiagnosticsReport#activityTrail()} entry as a best guess -- there is no {@code
   * activityType} on that record to positively identify the actual BPMN end event, so the guess is
   * flagged with a trailing comment rather than presented as certain.
   */
  private static void appendLifecycleAssertion(
      StringBuilder snippet, ProcessDiagnosticsReport report) {
    if (report.active()) {
      snippet.append("    .isActive()\n");
      if (!report.currentActivities().isEmpty()) {
        snippet
            .append("    .isWaitingAt(")
            .append(
                report.currentActivities().stream()
                    .map(ActivityInfo::activityId)
                    .map(ProcessAssertionSnippetFormatter::quote)
                    .collect(Collectors.joining(", ")))
            .append(")\n");
      }
      return;
    }
    if (!report.activityTrail().isEmpty()) {
      final String lastActivityId =
          report.activityTrail().get(report.activityTrail().size() - 1).activityId();
      snippet
          .append("    .hasEndedAt(")
          .append(quote(lastActivityId))
          .append(") // verify this is the actual end-event activity id\n");
    }
  }

  /**
   * One {@code .hasVariable(name, "value")} call per variable rather than a single {@code
   * .hasVariables(Map.of(...))} -- {@code Map.of} only has overloads up to 10 key-value pairs, so a
   * report with more variables would otherwise generate code that doesn't compile. Redacted and
   * truncated values are commented out instead of asserted on, since neither is the real value.
   */
  private static void appendVariableAssertions(
      StringBuilder snippet, ProcessDiagnosticsReport report) {
    report
        .variables()
        .forEach(
            (name, value) -> {
              if (REDACTED_PLACEHOLDER.equals(value)) {
                snippet
                    .append("    // .hasVariable(")
                    .append(quote(name))
                    .append(", ...) -- value redacted, fill in manually\n");
                return;
              }
              if (value.contains(TRUNCATED_MARKER)) {
                snippet
                    .append("    // .hasVariable(")
                    .append(quote(name))
                    .append(", ...) -- value truncated, fill in manually\n");
                return;
              }
              snippet
                  .append("    .hasVariable(")
                  .append(quote(name))
                  .append(", ")
                  .append(quote(value))
                  .append(")\n");
            });
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }
}
