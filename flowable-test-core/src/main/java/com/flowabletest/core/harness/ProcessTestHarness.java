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
import org.flowable.common.engine.api.delegate.event.AbstractFlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic BPMN process-testing primitives, extracted from the task-completion / wait-state polling
 * boilerplate that otherwise gets duplicated in every Flowable test class. Every method operates on
 * process instance IDs, activity IDs, and candidate group names -- never a domain-specific concept.
 *
 * <p>{@code diagnosticsCollector} may be {@code null} (diagnostics disabled via {@code
 * flowable.test.diagnostics.enabled=false}), in which case failures from this class are unenriched.
 */
public final class ProcessTestHarness {

  private static final Logger log = LoggerFactory.getLogger(ProcessTestHarness.class);

  /**
   * Engine event types that can plausibly change the outcome of a pending {@link #poll} call.
   * Subscribed once, for the harness's whole lifetime, so a wait wakes up the instant the engine
   * actually does something relevant instead of on a fixed sleep interval. {@code
   * JOB_MOVED_TO_DEADLETTER} additionally drives the fail-fast check in {@link #poll}.
   */
  private static final FlowableEngineEventType[] WAIT_RELEVANT_EVENT_TYPES = {
    FlowableEngineEventType.TASK_CREATED,
    FlowableEngineEventType.TASK_COMPLETED,
    FlowableEngineEventType.ACTIVITY_COMPLETED,
    FlowableEngineEventType.PROCESS_STARTED,
    FlowableEngineEventType.PROCESS_COMPLETED,
    FlowableEngineEventType.PROCESS_COMPLETED_WITH_TERMINATE_END_EVENT,
    FlowableEngineEventType.PROCESS_COMPLETED_WITH_ERROR_END_EVENT,
    FlowableEngineEventType.PROCESS_COMPLETED_WITH_ESCALATION_END_EVENT,
    FlowableEngineEventType.PROCESS_CANCELLED,
    FlowableEngineEventType.TIMER_FIRED,
    FlowableEngineEventType.JOB_EXECUTION_SUCCESS,
    FlowableEngineEventType.JOB_EXECUTION_FAILURE,
    FlowableEngineEventType.JOB_MOVED_TO_DEADLETTER,
  };

  private final RuntimeService runtimeService;
  private final TaskService taskService;
  private final HistoryService historyService;
  private final ManagementService managementService;
  private final RepositoryService repositoryService;
  private final String processesRoot;
  private final ProcessDiagnosticsCollector diagnosticsCollector;
  private final Object activitySignal = new Object();

  /**
   * Incremented under {@code synchronized (activitySignal)} every time {@link
   * ActivitySignalListener} fires. {@link #poll} captures this value before evaluating {@code
   * attempt}, then only calls {@link Object#wait(long)} if it's still unchanged -- otherwise an
   * engine event firing between the {@code attempt} check and the {@code wait()} call would {@code
   * notifyAll()} before anything is blocked on the monitor yet, and that wake-up would be silently
   * lost: the classic wait/notify missed-signal race. Without this guard, a lost wake-up means the
   * waiting thread sleeps out the entire remaining timeout instead of resolving immediately,
   * silently reintroducing the slow-wait behavior this event-driven design exists to avoid.
   */
  private long activityGeneration;

  public ProcessTestHarness(
      RuntimeService runtimeService,
      TaskService taskService,
      HistoryService historyService,
      ManagementService managementService,
      RepositoryService repositoryService,
      String processesRoot,
      ProcessDiagnosticsCollector diagnosticsCollector) {
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.managementService = managementService;
    this.repositoryService = repositoryService;
    this.processesRoot = processesRoot;
    this.diagnosticsCollector = diagnosticsCollector;
    runtimeService.addEventListener(new ActivitySignalListener(), WAIT_RELEVANT_EVENT_TYPES);
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

  /**
   * Deploys {@code <processesRoot>/<processName>.bpmn20.xml} as its own single-resource deployment
   * -- the programmatic escape hatch (layer 3 of the process-deployment allow-list) for a process
   * only one test in the whole suite needs, not worth declaring via {@code
   * flowable.test.processes.deploy} or {@code @FlowableProcessTest(processes = ...)}. {@code
   * enableDuplicateFiltering()} keeps a repeated call for the same process idempotent -- no new
   * process-definition version is created if the BPMN content hasn't changed.
   *
   * @param processName the BPMN <b>file</b> name (e.g. {@code "order-processing"}), not the {@code
   *     <process id="...">} declared inside it (e.g. {@code "orderProcessing"}) -- the two commonly
   *     differ by hyphenation
   */
  public void deployProcess(String processName) {
    repositoryService
        .createDeployment()
        .name(processName)
        .addClasspathResource(
            stripClasspathPrefix(processesRoot) + "/" + processName + ".bpmn20.xml")
        .enableDuplicateFiltering()
        .deploy();
  }

  private static String stripClasspathPrefix(String root) {
    String stripped = root;
    if (stripped.startsWith("classpath*:")) {
      stripped = stripped.substring("classpath*:".length());
    } else if (stripped.startsWith("classpath:")) {
      stripped = stripped.substring("classpath:".length());
    }
    while (stripped.startsWith("/")) {
      stripped = stripped.substring(1);
    }
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  public boolean hasEnded(String processInstanceId) {
    return runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count()
        == 0;
  }

  /**
   * Waits until the process instance has ended, waking up on the engine's own activity/task/job
   * events rather than sleeping on a fixed interval. Throws once {@code timeout} elapses, or
   * immediately (well before {@code timeout}) if a dead-letter job appears on the instance -- see
   * {@link #poll}.
   */
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
   * Waits until a task with the given candidate group appears on the process instance. See {@link
   * #awaitEnded} for the wake-up and fail-fast behavior.
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
   * Waits until a call-activity child process instance of the given definition key appears under
   * {@code parentProcessInstanceId} (a call activity's child has its own process instance ID, so it
   * must be located via {@code superProcessInstanceId}, not the parent's ID). See {@link
   * #awaitEnded} for the wake-up and fail-fast behavior.
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

  /**
   * Polls {@code attempt} until it returns non-null or {@code timeout} elapses. Rather than
   * sleeping on a fixed interval, each cycle blocks on {@code activitySignal} until the engine's
   * own {@link ActivitySignalListener} wakes it -- registered once, in the constructor, for the
   * event types in {@link #WAIT_RELEVANT_EVENT_TYPES} -- so a wait resolves the moment the engine
   * actually advances (typically on the async job executor thread or a Kafka Event Registry
   * consumer thread) instead of up to one poll interval late. Also fails fast, before {@code
   * timeout} elapses, the moment a dead-letter job appears for {@code diagnosticsProcessInstanceId}
   * -- an async delegate that threw does not fail the awaiting test directly, it gets silently
   * parked as a dead-letter job, and without this check the wait would otherwise run out its full
   * timeout for a state that will never arrive.
   */
  private <T> T poll(
      Duration timeout,
      Supplier<T> attempt,
      String description,
      String diagnosticsProcessInstanceId) {
    final Instant deadline = Instant.now().plus(timeout);
    while (true) {
      final long generationBeforeAttempt = currentActivityGeneration();
      final T result = attempt.get();
      if (result != null) {
        return result;
      }
      failFastOnDeadLetterJob(diagnosticsProcessInstanceId);
      final long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
      if (remainingMillis <= 0) {
        throw withDiagnostics(
            new AssertionError("Timed out after " + timeout + " waiting for " + description),
            diagnosticsProcessInstanceId);
      }
      awaitEngineActivity(remainingMillis, generationBeforeAttempt);
    }
  }

  private long currentActivityGeneration() {
    synchronized (activitySignal) {
      return activityGeneration;
    }
  }

  /**
   * Blocks on {@code activitySignal} for up to {@code remainingMillis}, unless {@code
   * activityGeneration} has already advanced past {@code generationBeforeAttempt} -- meaning an
   * engine event fired after this poll cycle's {@code attempt} check ran, so the condition may
   * already be satisfiable and this cycle should re-check it immediately instead of blocking.
   */
  private void awaitEngineActivity(long remainingMillis, long generationBeforeAttempt) {
    synchronized (activitySignal) {
      if (activityGeneration != generationBeforeAttempt) {
        return;
      }
      try {
        activitySignal.wait(remainingMillis);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting a BPMN engine event", e);
      }
    }
  }

  private void failFastOnDeadLetterJob(String processInstanceId) {
    final boolean hasDeadLetterJob =
        !managementService
            .createDeadLetterJobQuery()
            .processInstanceId(processInstanceId)
            .list()
            .isEmpty();
    if (hasDeadLetterJob) {
      throw withDiagnostics(
          new AssertionError(
              "Process instance <"
                  + processInstanceId
                  + "> has a dead-letter job -- see attached diagnostics for the failure"),
          processInstanceId);
    }
  }

  /**
   * Attaches a BPMN diagnostics snapshot of {@code processInstanceId} to {@code failure} as a
   * suppressed exception, then returns it for the caller to throw. A no-op (returns {@code failure}
   * unchanged) when diagnostics are disabled ({@code diagnosticsCollector} is {@code null}) or when
   * collection itself fails -- a flaky DB connection is a realistic case right at the moment
   * something has already gone wrong, and that must never replace the real failure being reported
   * with an unrelated exception, so a collection failure is logged and swallowed here instead.
   */
  private <T extends Throwable> T withDiagnostics(T failure, String processInstanceId) {
    if (diagnosticsCollector == null) {
      return failure;
    }
    try {
      failure.addSuppressed(
          new ProcessDiagnosticsAttachment(
              ProcessDiagnosticsFormatter.format(diagnosticsCollector.collect(processInstanceId))));
    } catch (final RuntimeException diagnosticsFailure) {
      log.warn(
          "Failed to collect Flowable process diagnostics for a harness failure on process "
              + "instance <{}>",
          processInstanceId,
          diagnosticsFailure);
    }
    return failure;
  }

  /**
   * Advances {@link #activityGeneration} and wakes every thread blocked in {@link
   * #awaitEngineActivity} on any relevant engine event. Deliberately does no work beyond that -- no
   * query, no business logic -- so it stays safe to invoke synchronously from whichever thread the
   * engine fires the event on (the async job executor, a Kafka Event Registry consumer thread, or
   * the calling thread itself), and {@link #isFailOnException()} returns {@code false} so a defect
   * here can never surface as a failure in the engine event dispatch it rides on. A non-static
   * inner class so it can advance the enclosing harness's {@link #activityGeneration} under the
   * same {@link #activitySignal} monitor {@link #poll} reads it under.
   */
  private final class ActivitySignalListener extends AbstractFlowableEventListener {

    @Override
    public void onEvent(FlowableEvent event) {
      synchronized (activitySignal) {
        activityGeneration++;
        activitySignal.notifyAll();
      }
    }

    @Override
    public boolean isFailOnException() {
      return false;
    }
  }
}
