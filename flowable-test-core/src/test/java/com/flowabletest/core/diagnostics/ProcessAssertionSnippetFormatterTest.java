package com.flowabletest.core.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityTrailEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests against hand-built {@link ProcessDiagnosticsReport} records -- no Flowable engine
 * needed, since this formatter only ever reads already-collected report fields.
 */
class ProcessAssertionSnippetFormatterTest {

  @Test
  void emitsIsActiveAndIsWaitingAtForAnActiveInstanceWithACurrentActivity() {
    final ProcessDiagnosticsReport report =
        report(true, List.of(activity("reviewTask")), Map.of(), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet)
        .contains("processTestHarness.assertThat(\"instance-1\")")
        .contains(".isActive()")
        .contains(".isWaitingAt(\"reviewTask\")")
        .doesNotContain(".hasEndedAt");
  }

  @Test
  void omitsIsWaitingAtWhenActiveButNoCurrentActivities() {
    final ProcessDiagnosticsReport report = report(true, List.of(), Map.of(), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet).contains(".isActive()").doesNotContain(".isWaitingAt");
  }

  @Test
  void emitsHasEndedAtUsingTheLastActivityTrailEntryWithAVerifyComment() {
    final ProcessDiagnosticsReport report =
        report(
            false,
            List.of(),
            Map.of(),
            List.of(trailEntry("startEvent"), trailEntry("reviewTask"), trailEntry("endEvent")));

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet)
        .contains(".hasEndedAt(\"endEvent\") // verify this is the actual end-event activity id")
        .doesNotContain(".isActive()")
        .doesNotContain(".isWaitingAt");
  }

  @Test
  void omitsHasEndedAtWhenEndedWithNoActivityTrail() {
    final ProcessDiagnosticsReport report = report(false, List.of(), Map.of(), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet).doesNotContain(".hasEndedAt");
  }

  @Test
  void emitsOneHasVariableCallPerVariableAsQuotedStrings() {
    final ProcessDiagnosticsReport report =
        report(true, List.of(), Map.of("orderId", "42", "status", "APPROVED"), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet)
        .contains(".hasVariable(\"orderId\", \"42\")")
        .contains(".hasVariable(\"status\", \"APPROVED\")")
        .contains("// variable values below are copied as Strings");
  }

  @Test
  void commentsOutRedactedAndTruncatedVariablesInsteadOfAssertingOnThem() {
    final ProcessDiagnosticsReport report =
        report(
            true,
            List.of(),
            Map.of(
                "password", "[REDACTED]",
                "payload", "a-value... (truncated, 500 chars total)"),
            List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet)
        .contains("// .hasVariable(\"password\", ...) -- value redacted, fill in manually")
        .contains("// .hasVariable(\"payload\", ...) -- value truncated, fill in manually")
        .doesNotContain("\n    .hasVariable(\"password\"")
        .doesNotContain("\n    .hasVariable(\"payload\"");
  }

  @Test
  void omitsTheVariableTypingHeaderCommentWhenThereAreNoVariables() {
    final ProcessDiagnosticsReport report = report(true, List.of(), Map.of(), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet).doesNotContain("// variable values below are copied as Strings");
  }

  @Test
  void escapesQuotesAndBackslashesInQuotedValues() {
    final ProcessDiagnosticsReport report =
        report(true, List.of(), Map.of("note", "she said \"go\\stop\""), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet).contains(".hasVariable(\"note\", \"she said \\\"go\\\\stop\\\"\")");
  }

  @Test
  void alwaysEndsWithATerminatingSemicolon() {
    final ProcessDiagnosticsReport report = report(true, List.of(), Map.of(), List.of());

    final String snippet = ProcessAssertionSnippetFormatter.format(report);

    assertThat(snippet.stripTrailing()).endsWith(";");
  }

  private static ActivityInfo activity(String activityId) {
    return new ActivityInfo(activityId, "Review", "userTask", null, null);
  }

  private static ActivityTrailEntry trailEntry(String activityId) {
    return new ActivityTrailEntry(activityId, activityId, Instant.EPOCH, Instant.EPOCH);
  }

  private static ProcessDiagnosticsReport report(
      boolean active,
      List<ActivityInfo> currentActivities,
      Map<String, String> variables,
      List<ActivityTrailEntry> activityTrail) {
    return new ProcessDiagnosticsReport(
        "instance-1",
        "process-key",
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        active,
        currentActivities,
        new TreeMap<>(variables),
        List.of(),
        activityTrail,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }
}
