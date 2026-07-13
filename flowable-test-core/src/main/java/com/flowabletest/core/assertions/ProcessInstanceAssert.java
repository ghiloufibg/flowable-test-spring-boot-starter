package com.flowabletest.core.assertions;

import org.assertj.core.api.AbstractAssert;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;

/**
 * AssertJ-style assertions over a process instance ID. Deliberately domain-blind: every method
 * takes plain activity IDs / candidate group names as arguments, never a masterclass-specific
 * concept (design doc section 4.4).
 *
 * <p>Constructed via {@link com.flowabletest.core.harness.ProcessTestHarness#assertThat(String)}
 * rather than a bare static factory, since evaluating these assertions requires the consumer's own
 * {@code RuntimeService}/{@code HistoryService} beans.
 */
public final class ProcessInstanceAssert extends AbstractAssert<ProcessInstanceAssert, String> {

  private final RuntimeService runtimeService;
  private final HistoryService historyService;

  public ProcessInstanceAssert(
      String processInstanceId, RuntimeService runtimeService, HistoryService historyService) {
    super(processInstanceId, ProcessInstanceAssert.class);
    this.runtimeService = runtimeService;
    this.historyService = historyService;
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
      failWithMessage(
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
      failWithMessage(
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
      failWithMessage(
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
      failWithMessage(
          "Expected process instance <%s> to have no task for candidate group <%s>, but found %d",
          actual, candidateGroup, historicTasks);
    }
    return this;
  }
}
