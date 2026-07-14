package com.flowabletest.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.ActivityTrailEntry;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.FailedJobInfo;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsReport.PendingTaskInfo;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;

/**
 * Turns a process instance ID into a full {@link ProcessDiagnosticsReport} snapshot -- current
 * activity, variables, activity trail, pending tasks, and dead-letter job failures. Invoked by
 * {@link FlowableProcessDiagnosticsExtension} and by {@code ProcessTestHarness} whenever a test
 * fails; the resulting report is rendered by {@link ProcessDiagnosticsFormatter}. Dead-letter jobs
 * are the highest-value field here: an async service task that throws does not fail the test
 * directly, it gets silently parked as a dead-letter job while the test thread just sees an
 * unrelated timeout.
 *
 * <p>Variable values are otherwise dumped verbatim into text that routinely ends up archived in CI
 * (Surefire reports, log aggregation), so any variable whose name contains one of {@code
 * redactedVariableNamePatterns} (case-insensitive substring match) is rendered as {@code
 * [REDACTED]} instead of its real value, before truncation.
 *
 * <p>Object-type variables (a POJO, record, {@code List}, or {@code Map} -- anything Flowable
 * itself doesn't treat as a primitive) render as JSON rather than {@code Object#toString()}, since
 * an undocumented value class's default {@code toString()} is just a hash code, telling a reader
 * nothing about what the variable actually held when the test failed. Falls back to {@code
 * toString()} if Jackson itself can't serialize the value (a lazy-loading proxy, a getter that
 * throws), since a diagnostics-rendering failure must never be allowed to replace the real test
 * failure it was trying to enrich.
 */
public final class ProcessDiagnosticsCollector {

  /**
   * Flowable records a {@code HistoricActivityInstance} for every sequence-flow transition, not
   * just BPMN nodes -- pure noise for a human reading "what was this process doing," so both
   * current-activity and activity-trail queries exclude it.
   */
  private static final String SEQUENCE_FLOW_ACTIVITY_TYPE = "sequenceFlow";

  private static final String REDACTED_PLACEHOLDER = "[REDACTED]";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final RuntimeService runtimeService;
  private final TaskService taskService;
  private final HistoryService historyService;
  private final ManagementService managementService;
  private final int maxActivityTrailEntries;
  private final int maxVariableValueLength;
  private final boolean includeFailedJobs;
  private final List<String> redactedVariableNamePatterns;

  public ProcessDiagnosticsCollector(
      RuntimeService runtimeService,
      TaskService taskService,
      HistoryService historyService,
      ManagementService managementService,
      int maxActivityTrailEntries,
      int maxVariableValueLength,
      boolean includeFailedJobs,
      List<String> redactedVariableNamePatterns) {
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.managementService = managementService;
    this.maxActivityTrailEntries = maxActivityTrailEntries;
    this.maxVariableValueLength = maxVariableValueLength;
    this.includeFailedJobs = includeFailedJobs;
    this.redactedVariableNamePatterns =
        redactedVariableNamePatterns.stream()
            .map(pattern -> pattern.toLowerCase(Locale.ROOT))
            .toList();
  }

  /** Collects a full snapshot for one process instance, active or already ended. */
  public ProcessDiagnosticsReport collect(String processInstanceId) {
    final ProcessInstance instance =
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();
    final boolean active = instance != null;

    final String processDefinitionKey;
    final Integer processDefinitionVersion;
    final String businessKey;
    if (active) {
      processDefinitionKey = instance.getProcessDefinitionKey();
      processDefinitionVersion = instance.getProcessDefinitionVersion();
      businessKey = instance.getBusinessKey();
    } else {
      final HistoricProcessInstance historicInstance =
          historyService
              .createHistoricProcessInstanceQuery()
              .processInstanceId(processInstanceId)
              .singleResult();
      processDefinitionKey =
          historicInstance == null ? null : historicInstance.getProcessDefinitionKey();
      processDefinitionVersion =
          historicInstance == null ? null : historicInstance.getProcessDefinitionVersion();
      businessKey = historicInstance == null ? null : historicInstance.getBusinessKey();
    }

    return new ProcessDiagnosticsReport(
        processInstanceId,
        processDefinitionKey,
        processDefinitionVersion,
        businessKey,
        active,
        currentActivities(processInstanceId),
        variables(processInstanceId, active),
        activityTrail(processInstanceId),
        pendingTasks(processInstanceId),
        includeFailedJobs ? failedJobs(processInstanceId) : List.of());
  }

  private List<ActivityInfo> currentActivities(String processInstanceId) {
    return historyService
        .createHistoricActivityInstanceQuery()
        .processInstanceId(processInstanceId)
        .unfinished()
        .list()
        .stream()
        .filter(a -> !SEQUENCE_FLOW_ACTIVITY_TYPE.equals(a.getActivityType()))
        .map(a -> new ActivityInfo(a.getActivityId(), a.getActivityName(), a.getActivityType()))
        .toList();
  }

  private Map<String, String> variables(String processInstanceId, boolean active) {
    final Map<String, Object> raw;
    if (active) {
      raw = runtimeService.getVariables(processInstanceId);
    } else {
      raw =
          historyService
              .createHistoricVariableInstanceQuery()
              .processInstanceId(processInstanceId)
              .list()
              .stream()
              .collect(
                  Collectors.toMap(
                      HistoricVariableInstance::getVariableName,
                      HistoricVariableInstance::getValue,
                      (first, second) -> first));
    }
    final Map<String, String> rendered = new TreeMap<>();
    raw.forEach((name, value) -> rendered.put(name, renderValue(name, value)));
    return rendered;
  }

  private String renderValue(String variableName, Object value) {
    if (isRedacted(variableName)) {
      return REDACTED_PLACEHOLDER;
    }
    if (value == null) {
      return "null";
    }
    if (value instanceof byte[] bytes) {
      return "byte[" + bytes.length + "]";
    }
    final String text = isSimpleType(value) ? String.valueOf(value) : renderAsJson(value);
    if (text.length() <= maxVariableValueLength) {
      return text;
    }
    return text.substring(0, maxVariableValueLength)
        + "... (truncated, "
        + text.length()
        + " chars total)";
  }

  /**
   * Types Flowable itself treats as primitives, and that already have a meaningful {@code
   * toString()} -- JSON-encoding a {@code String} would just add a distracting pair of quotes
   * around the overwhelming majority of process variables for no benefit.
   */
  private static boolean isSimpleType(Object value) {
    return value instanceof CharSequence
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Enum<?>
        || value instanceof Date
        || value instanceof TemporalAccessor
        || value instanceof UUID;
  }

  private static String renderAsJson(Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (final Exception jsonSerializationFailure) {
      return String.valueOf(value);
    }
  }

  private boolean isRedacted(String variableName) {
    final String lowerCaseName = variableName.toLowerCase(Locale.ROOT);
    return redactedVariableNamePatterns.stream().anyMatch(lowerCaseName::contains);
  }

  private List<ActivityTrailEntry> activityTrail(String processInstanceId) {
    final List<HistoricActivityInstance> entries =
        historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(processInstanceId)
            .orderByHistoricActivityInstanceStartTime()
            .asc()
            .list()
            .stream()
            .filter(a -> !SEQUENCE_FLOW_ACTIVITY_TYPE.equals(a.getActivityType()))
            .toList();
    final int fromIndex = Math.max(0, entries.size() - maxActivityTrailEntries);
    return entries.subList(fromIndex, entries.size()).stream()
        .map(
            a ->
                new ActivityTrailEntry(
                    a.getActivityId(),
                    a.getActivityName(),
                    toInstant(a.getStartTime()),
                    toInstant(a.getEndTime())))
        .toList();
  }

  private List<PendingTaskInfo> pendingTasks(String processInstanceId) {
    return taskService.createTaskQuery().processInstanceId(processInstanceId).list().stream()
        .map(
            task ->
                new PendingTaskInfo(
                    task.getId(), task.getName(), task.getAssignee(), candidateGroups(task)))
        .toList();
  }

  private List<String> candidateGroups(Task task) {
    return taskService.getIdentityLinksForTask(task.getId()).stream()
        .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType()))
        .map(IdentityLink::getGroupId)
        .filter(Objects::nonNull)
        .toList();
  }

  private List<FailedJobInfo> failedJobs(String processInstanceId) {
    return managementService
        .createDeadLetterJobQuery()
        .processInstanceId(processInstanceId)
        .list()
        .stream()
        .map(
            job ->
                new FailedJobInfo(
                    job.getId(),
                    job.getElementId(),
                    job.getExceptionMessage(),
                    managementService.getDeadLetterJobExceptionStacktrace(job.getId()),
                    job.getRetries()))
        .toList();
  }

  private static Instant toInstant(Date date) {
    return date == null ? null : date.toInstant();
  }
}
