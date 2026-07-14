package com.flowabletest.core.assertions;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsAttachment;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsFormatter;
import org.assertj.core.api.AbstractAssert;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;

/**
 * AssertJ-style assertions over a process instance ID. Deliberately domain-blind: every method
 * takes plain activity IDs / candidate group names as arguments, never a project-specific concept.
 *
 * <p>Constructed via {@link com.flowabletest.core.harness.ProcessTestHarness#assertThat(String)}
 * rather than a bare static factory, since evaluating these assertions requires the consumer's own
 * {@code RuntimeService}/{@code HistoryService} beans.
 *
 * <p>Every failure message is enriched with a BPMN diagnostics snapshot (current activity,
 * variables, activity trail, pending tasks, dead-letter job failures) of exactly this process
 * instance, since the failing assertion already knows precisely which one is relevant. {@code
 * diagnosticsCollector} may be {@code null} (diagnostics disabled via {@code
 * flowable.test.diagnostics.enabled=false}), in which case failure messages are unenriched.
 */
public final class ProcessInstanceAssert extends AbstractAssert<ProcessInstanceAssert, String> {

  private final RuntimeService runtimeService;
  private final HistoryService historyService;
  private final ProcessDiagnosticsCollector diagnosticsCollector;

  public ProcessInstanceAssert(
      String processInstanceId,
      RuntimeService runtimeService,
      HistoryService historyService,
      ProcessDiagnosticsCollector diagnosticsCollector) {
    super(processInstanceId, ProcessInstanceAssert.class);
    this.runtimeService = runtimeService;
    this.historyService = historyService;
    this.diagnosticsCollector = diagnosticsCollector;
  }

  /**
   * The process instance has no active execution left, and history shows it reached {@code
   * activityId}.
   */
  public ProcessInstanceAssert hasEndedAt(String activityId) {
    isNotNull();

    final long active =
        runtimeService.createProcessInstanceQuery().processInstanceId(actual).count();
    if (active != 0) {
      failWithDiagnostics(
          "Expected process instance <%s> to have ended, but it is still active", actual);
    }

    final boolean reachedActivity =
        !historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(actual)
            .activityId(activityId)
            .list()
            .isEmpty();
    if (!reachedActivity) {
      failWithDiagnostics(
          "Expected process instance <%s> to have reached end activity <%s>, but it did not",
          actual, activityId);
    }
    return this;
  }

  /** The process instance still has at least one active execution (a genuine wait state). */
  public ProcessInstanceAssert isActive() {
    isNotNull();
    final long active =
        runtimeService.createProcessInstanceQuery().processInstanceId(actual).count();
    if (active == 0) {
      failWithDiagnostics(
          "Expected process instance <%s> to still be active, but it has ended", actual);
    }
    return this;
  }

  /** No task exists for this process instance with the given candidate group. */
  public ProcessInstanceAssert hasNoTaskForCandidateGroup(String candidateGroup) {
    isNotNull();
    // Deliberately routed through TaskService in the harness rather than duplicated here;
    // kept as a HistoryService-only check so this assertion type has no TaskService dependency.
    final long historicTasks =
        historyService
            .createHistoricTaskInstanceQuery()
            .processInstanceId(actual)
            .taskCandidateGroup(candidateGroup)
            .count();
    if (historicTasks != 0) {
      failWithDiagnostics(
          "Expected process instance <%s> to have no task for candidate group <%s>, but found %d",
          actual, candidateGroup, historicTasks);
    }
    return this;
  }

  /**
   * Same contract as {@link #failWithMessage(String, Object...)} (always throws), but attaches a
   * BPMN diagnostics snapshot of this process instance as a suppressed exception first. Diagnostics
   * text is never interpolated into the format string itself -- a variable value containing a
   * literal {@code %} would otherwise corrupt {@code String.format} evaluation.
   */
  private void failWithDiagnostics(String errorMessage, Object... arguments) {
    try {
      failWithMessage(errorMessage, arguments);
    } catch (final AssertionError failure) {
      if (diagnosticsCollector != null) {
        failure.addSuppressed(
            new ProcessDiagnosticsAttachment(
                ProcessDiagnosticsFormatter.format(diagnosticsCollector.collect(actual))));
      }
      throw failure;
    }
  }
}
