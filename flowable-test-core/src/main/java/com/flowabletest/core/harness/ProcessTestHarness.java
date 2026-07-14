package com.flowabletest.core.harness;

import com.flowabletest.core.assertions.ProcessInstanceAssert;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsAttachment;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsFormatter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;

/**
 * Generic BPMN process-testing primitives, extracted from the task-completion / wait-state polling
 * boilerplate that otherwise gets duplicated in every Flowable test class. Every method operates on
 * process instance IDs, activity IDs, and candidate group names -- never a domain-specific concept
 * (design doc section 4.4).
 *
 * <p>{@code diagnosticsCollector} may be {@code null} (diagnostics disabled via {@code
 * flowable.test.diagnostics.enabled=false}), in which case failures from this class are unenriched,
 * exactly as before that capability existed -- see {@code
 * claudedocs/bpmn-failure-diagnostics-design.md}.
 */
public final class ProcessTestHarness {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);

  private final RuntimeService runtimeService;
  private final TaskService taskService;
  private final HistoryService historyService;
  private final ProcessDiagnosticsCollector diagnosticsCollector;

  public ProcessTestHarness(
      RuntimeService runtimeService,
      TaskService taskService,
      HistoryService historyService,
      ProcessDiagnosticsCollector diagnosticsCollector) {
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.diagnosticsCollector = diagnosticsCollector;
  }

  public ProcessInstanceAssert assertThat(String processInstanceId) {
    return new ProcessInstanceAssert(
        processInstanceId, runtimeService, historyService, diagnosticsCollector);
  }

  /**
   * The single active task for this process instance with the given candidate group, failing loudly
   * if there isn't exactly one.
   */
  public Task findSingleTask(String processInstanceId, String candidateGroup) {
    final List<Task> tasks =
        taskService
            .createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskCandidateGroup(candidateGroup)
            .list();
    if (tasks.size() != 1) {
      throw withDiagnostics(
          new IllegalStateException(
              "Expected exactly one task with candidate group '"
                  + candidateGroup
                  + "' for process instance "
                  + processInstanceId
                  + " but found "
                  + tasks.size()),
          processInstanceId);
    }
    return tasks.get(0);
  }

  /**
   * Completes the single task found via {@link #findSingleTask}, returning the task that was
   * completed.
   */
  public Task completeSingleTask(
      String processInstanceId, String candidateGroup, Map<String, Object> variables) {
    final Task task = findSingleTask(processInstanceId, candidateGroup);
    taskService.complete(task.getId(), variables);
    return task;
  }

  public boolean hasEnded(String processInstanceId) {
    return runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count()
        == 0;
  }

  /** Polls until the process instance has ended, or throws once {@code timeout} elapses. */
  public HistoricProcessInstance awaitEnded(String processInstanceId, Duration timeout) {
    return poll(
        timeout,
        () ->
            hasEnded(processInstanceId)
                ? historyService
                    .createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult()
                : null,
        "process instance <" + processInstanceId + "> to end",
        processInstanceId);
  }

  /**
   * Polls until a task with the given candidate group appears on the process instance, or throws
   * once {@code timeout} elapses.
   */
  public Task awaitTaskForCandidateGroup(
      String processInstanceId, String candidateGroup, Duration timeout) {
    return poll(
        timeout,
        () -> {
          final List<Task> tasks =
              taskService
                  .createTaskQuery()
                  .processInstanceId(processInstanceId)
                  .taskCandidateGroup(candidateGroup)
                  .list();
          return tasks.isEmpty() ? null : tasks.get(0);
        },
        "a task with candidate group <"
            + candidateGroup
            + "> on process instance <"
            + processInstanceId
            + ">",
        processInstanceId);
  }

  /**
   * Polls until a call-activity child process instance of the given definition key appears under
   * {@code parentProcessInstanceId} (a call activity's child has its own process instance ID, so it
   * must be located via {@code superProcessInstanceId}, not the parent's ID).
   */
  public HistoricProcessInstance awaitCallActivityChild(
      String parentProcessInstanceId, String childProcessDefinitionKey, Duration timeout) {
    return poll(
        timeout,
        () ->
            historyService
                .createHistoricProcessInstanceQuery()
                .superProcessInstanceId(parentProcessInstanceId)
                .processDefinitionKey(childProcessDefinitionKey)
                .singleResult(),
        "a child process instance of <"
            + childProcessDefinitionKey
            + "> under parent <"
            + parentProcessInstanceId
            + ">",
        parentProcessInstanceId);
  }

  private <T> T poll(
      Duration timeout,
      Supplier<T> attempt,
      String description,
      String diagnosticsProcessInstanceId) {
    final Instant deadline = Instant.now().plus(timeout);
    while (true) {
      final T result = attempt.get();
      if (result != null) {
        return result;
      }
      if (Instant.now().isAfter(deadline)) {
        throw withDiagnostics(
            new AssertionError("Timed out after " + timeout + " waiting for " + description),
            diagnosticsProcessInstanceId);
      }
      sleep(DEFAULT_POLL_INTERVAL);
    }
  }

  /**
   * Attaches a BPMN diagnostics snapshot of {@code processInstanceId} to {@code failure} as a
   * suppressed exception, then returns it for the caller to throw. A no-op (returns {@code failure}
   * unchanged) when diagnostics are disabled ({@code diagnosticsCollector} is {@code null}) -- see
   * {@code claudedocs/bpmn-failure-diagnostics-design.md}.
   */
  private <T extends Throwable> T withDiagnostics(T failure, String processInstanceId) {
    if (diagnosticsCollector != null) {
      failure.addSuppressed(
          new ProcessDiagnosticsAttachment(
              ProcessDiagnosticsFormatter.format(diagnosticsCollector.collect(processInstanceId))));
    }
    return failure;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while polling", e);
    }
  }
}
