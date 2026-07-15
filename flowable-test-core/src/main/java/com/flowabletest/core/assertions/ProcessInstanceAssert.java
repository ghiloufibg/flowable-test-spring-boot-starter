package com.flowabletest.core.assertions;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsAttachment;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsFormatter;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.assertj.core.api.AbstractAssert;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AssertJ-style assertions over a process instance ID. Deliberately domain-blind: every method
 * takes plain activity IDs or candidate group names as arguments, never a project-specific concept.
 *
 * <p>Obtained from {@link ProcessTestHarness#assertThat(String)} rather than a bare static factory,
 * since evaluating these assertions requires the consumer's own {@code RuntimeService} and {@code
 * HistoryService} beans.
 *
 * <p>Every failure message is enriched with a BPMN diagnostics snapshot of exactly the process
 * instance under test — current activity, variables, activity trail, pending tasks, and dead-letter
 * job failures — attached as a suppressed exception. Diagnostics are omitted from the failure
 * message when {@code flowable.test.diagnostics.enabled=false} leaves the collector {@code null}.
 */
public final class ProcessInstanceAssert extends AbstractAssert<ProcessInstanceAssert, String> {

  private static final Logger log = LoggerFactory.getLogger(ProcessInstanceAssert.class);

  /**
   * Flowable records a {@code HistoricActivityInstance} for every sequence-flow transition, not
   * just BPMN nodes -- never a place a process instance meaningfully "waits," so {@link
   * #isWaitingAt} excludes it.
   */
  private static final String SEQUENCE_FLOW_ACTIVITY_TYPE = "sequenceFlow";

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
            .unfinished()
            .count();
    if (historicTasks != 0) {
      failWithDiagnostics(
          "Expected process instance <%s> to have no task for candidate group <%s>, but found %d",
          actual, candidateGroup, historicTasks);
    }
    return this;
  }

  /**
   * The process instance is active and its current unfinished activities are exactly {@code
   * activityIds} (order-independent) -- parallel branches, including a parallel multi-instance
   * activity, can legitimately leave more than one. Precise by design, the same way {@link
   * #hasEndedAt} pins down a single end activity: an unexpected extra wait state is a real defect
   * this assertion is meant to catch, not something to silently tolerate.
   */
  public ProcessInstanceAssert isWaitingAt(String... activityIds) {
    isActive();
    final Set<String> expectedActivityIds = Set.of(activityIds);
    final Set<String> actualActivityIds = new HashSet<>(currentActivityIds());
    if (!expectedActivityIds.equals(actualActivityIds)) {
      failWithDiagnostics(
          "Expected process instance <%s> to be waiting at <%s> but was waiting at <%s>",
          actual, expectedActivityIds, actualActivityIds);
    }
    return this;
  }

  private List<String> currentActivityIds() {
    return historyService
        .createHistoricActivityInstanceQuery()
        .processInstanceId(actual)
        .unfinished()
        .list()
        .stream()
        .filter(a -> !SEQUENCE_FLOW_ACTIVITY_TYPE.equals(a.getActivityType()))
        .map(HistoricActivityInstance::getActivityId)
        .toList();
  }

  /**
   * The process instance (active or already ended) has variable {@code name} equal to {@code
   * expectedValue}, resolved from live runtime state while active and from history once ended -- so
   * this assertion works identically before and after {@code hasEndedAt} in the same test.
   */
  public ProcessInstanceAssert hasVariable(String name, Object expectedValue) {
    isNotNull();
    final Object actualValue = resolveVariable(name);
    if (!Objects.equals(actualValue, expectedValue)) {
      failWithDiagnostics(
          "Expected process instance <%s> to have variable <%s> equal to <%s> but was <%s>",
          actual, name, expectedValue, actualValue);
    }
    return this;
  }

  /**
   * The process instance (active or already ended) has every variable in {@code expectedVariables}
   * equal to its given value; variables the process instance holds but that aren't listed here are
   * ignored, so a caller can assert on just the variables relevant to the test without enumerating
   * every variable the process happens to carry.
   */
  public ProcessInstanceAssert hasVariables(Map<String, Object> expectedVariables) {
    isNotNull();
    expectedVariables.forEach(this::hasVariable);
    return this;
  }

  private Object resolveVariable(String name) {
    final long active =
        runtimeService.createProcessInstanceQuery().processInstanceId(actual).count();
    if (active != 0) {
      return runtimeService.getVariable(actual, name);
    }
    final HistoricVariableInstance historicVariable =
        historyService
            .createHistoricVariableInstanceQuery()
            .processInstanceId(actual)
            .variableName(name)
            .singleResult();
    return historicVariable == null ? null : historicVariable.getValue();
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
      attachDiagnostics(failure);
      throw failure;
    }
  }

  /**
   * Diagnostics collection failing (a flaky DB connection is a realistic case, right at the moment
   * something has already gone wrong) must never replace the real assertion failure with an
   * unrelated exception -- so a collection failure is logged and swallowed here, never rethrown.
   */
  private void attachDiagnostics(AssertionError failure) {
    if (diagnosticsCollector == null) {
      return;
    }
    try {
      failure.addSuppressed(
          new ProcessDiagnosticsAttachment(
              ProcessDiagnosticsFormatter.format(diagnosticsCollector.collect(actual))));
    } catch (final RuntimeException diagnosticsFailure) {
      log.warn(
          "Failed to collect Flowable process diagnostics for a failing assertion on process "
              + "instance <{}>",
          actual,
          diagnosticsFailure);
    }
  }
}
