package com.flowabletest.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricDetail;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricVariableUpdate;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.flowable.variable.api.history.HistoricVariableInstance;

/**
 * Turns a process instance ID into a full {@link ProcessDiagnosticsReport} snapshot -- process
 * definition metadata, current activity (including multi-instance loop counters and, for a call
 * activity, the spawned child instance ID), variable history, gateway trace, activity trail,
 * pending and completed tasks, both pending and dead-letter job state, and which test started the
 * instance (via {@link ProcessInstanceTracker#testOriginFor}). Invoked by {@link
 * FlowableProcessDiagnosticsExtension} and by {@code ProcessTestHarness} whenever a test fails; the
 * resulting report is rendered by {@link ProcessDiagnosticsFormatter}. Dead-letter jobs are the
 * highest-value field here: an async service task that throws does not fail the test directly, it
 * gets silently parked as a dead-letter job while the test thread just sees an unrelated timeout.
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
 * failure it was trying to enrich. Process-definition metadata lookup follows the same rule -- a
 * failed {@code getProcessDefinition} call degrades to nulls rather than propagating.
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
  private final RepositoryService repositoryService;
  private final ProcessInstanceTracker processInstanceTracker;
  private final int maxActivityTrailEntries;
  private final int maxVariableValueLength;
  private final int maxVariableHistoryEntries;
  private final boolean includeFailedJobs;
  private final List<String> redactedVariableNamePatterns;

  public ProcessDiagnosticsCollector(
      RuntimeService runtimeService,
      TaskService taskService,
      HistoryService historyService,
      ManagementService managementService,
      RepositoryService repositoryService,
      ProcessInstanceTracker processInstanceTracker,
      int maxActivityTrailEntries,
      int maxVariableValueLength,
      int maxVariableHistoryEntries,
      boolean includeFailedJobs,
      List<String> redactedVariableNamePatterns) {
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.managementService = managementService;
    this.repositoryService = repositoryService;
    this.processInstanceTracker = processInstanceTracker;
    this.maxActivityTrailEntries = maxActivityTrailEntries;
    this.maxVariableValueLength = maxVariableValueLength;
    this.maxVariableHistoryEntries = maxVariableHistoryEntries;
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

    // Queried regardless of active/ended: HistoricProcessInstance rows exist for a running
    // instance too (Flowable writes history live, not only on completion), and it's the only
    // source for getSuperProcessInstanceId() -- the plain runtime ProcessInstance has no such
    // field.
    final HistoricProcessInstance historicInstance =
        historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();

    final String processDefinitionKey;
    final Integer processDefinitionVersion;
    final String processDefinitionId;
    final String businessKey;
    if (active) {
      processDefinitionKey = instance.getProcessDefinitionKey();
      processDefinitionVersion = instance.getProcessDefinitionVersion();
      processDefinitionId = instance.getProcessDefinitionId();
      businessKey = instance.getBusinessKey();
    } else {
      processDefinitionKey =
          historicInstance == null ? null : historicInstance.getProcessDefinitionKey();
      processDefinitionVersion =
          historicInstance == null ? null : historicInstance.getProcessDefinitionVersion();
      processDefinitionId =
          historicInstance == null ? null : historicInstance.getProcessDefinitionId();
      businessKey = historicInstance == null ? null : historicInstance.getBusinessKey();
    }
    final String superProcessInstanceId =
        historicInstance == null ? null : historicInstance.getSuperProcessInstanceId();

    final ProcessDefinition processDefinition = processDefinition(processDefinitionId);

    return new ProcessDiagnosticsReport(
        processInstanceId,
        processDefinitionKey,
        processDefinitionVersion,
        processDefinition == null ? null : processDefinition.getName(),
        processDefinition == null ? null : processDefinition.getCategory(),
        processDefinition == null ? null : processDefinition.getDeploymentId(),
        processDefinition == null ? null : processDefinition.getResourceName(),
        deploymentTime(processDefinition),
        businessKey,
        superProcessInstanceId,
        active,
        currentActivities(processInstanceId, processDefinitionId),
        variables(processInstanceId, active),
        variableHistory(processInstanceId),
        activityTrail(processInstanceId),
        gatewayTrace(processInstanceId, processDefinitionId),
        pendingTasks(processInstanceId),
        completedTasks(processInstanceId),
        includeFailedJobs ? failedJobs(processInstanceId) : List.of(),
        pendingJobs(processInstanceId),
        processInstanceTracker.testOriginFor(processInstanceId));
  }

  private ProcessDefinition processDefinition(String processDefinitionId) {
    if (processDefinitionId == null) {
      return null;
    }
    try {
      return repositoryService.getProcessDefinition(processDefinitionId);
    } catch (final Exception processDefinitionLookupFailure) {
      return null;
    }
  }

  private Instant deploymentTime(ProcessDefinition processDefinition) {
    if (processDefinition == null) {
      return null;
    }
    final Deployment deployment =
        repositoryService
            .createDeploymentQuery()
            .deploymentId(processDefinition.getDeploymentId())
            .singleResult();
    return deployment == null ? null : toInstant(deployment.getDeploymentTime());
  }

  /**
   * A multi-instance activity has one {@code HistoricActivityInstance} per child execution -- three
   * rows for a loop cardinality of three, all sharing the same activity ID -- which would otherwise
   * surface as duplicate entries for what a reader thinks of as a single activity. Every child
   * after the first with a resolved {@link MultiInstanceProgress} is skipped, collapsing them into
   * the one row that carries the aggregate counters.
   */
  private List<ActivityInfo> currentActivities(
      String processInstanceId, String processDefinitionId) {
    final BpmnModel bpmnModel = bpmnModel(processDefinitionId);
    final List<ActivityInfo> activities = new ArrayList<>();
    final Set<String> multiInstanceActivityIdsSeen = new HashSet<>();
    for (final HistoricActivityInstance a :
        historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(processInstanceId)
            .unfinished()
            .list()) {
      if (SEQUENCE_FLOW_ACTIVITY_TYPE.equals(a.getActivityType())) {
        continue;
      }
      final MultiInstanceProgress progress =
          multiInstanceProgress(bpmnModel, processInstanceId, a.getActivityId());
      if (progress != null && !multiInstanceActivityIdsSeen.add(a.getActivityId())) {
        continue;
      }
      activities.add(
          new ActivityInfo(
              a.getActivityId(),
              a.getActivityName(),
              a.getActivityType(),
              progress,
              a.getCalledProcessInstanceId()));
    }
    return activities;
  }

  /**
   * {@code null} whenever the definition can't be resolved (deleted deployment, redacted history)
   * -- degrading gracefully here means multi-instance progress and gateway-trace both simply come
   * back empty instead of failing the whole snapshot.
   */
  private BpmnModel bpmnModel(String processDefinitionId) {
    if (processDefinitionId == null) {
      return null;
    }
    try {
      return repositoryService.getBpmnModel(processDefinitionId);
    } catch (final Exception bpmnModelLookupFailure) {
      return null;
    }
  }

  /**
   * The {@code nrOfInstances}/{@code nrOfActiveInstances}/{@code nrOfCompletedInstances} loop
   * counters Flowable maintains as local variables on a multi-instance activity's scope execution
   * -- distinct from the per-child-execution counters (each child only carries its own {@code
   * loopCounter}). {@link org.flowable.engine.runtime.ExecutionQuery#activityId} cannot be used to
   * find it: unlike {@link Execution#getActivityId()}, that query filter does not match the scope
   * execution, so every execution for the instance is fetched instead and filtered by the getter.
   */
  private MultiInstanceProgress multiInstanceProgress(
      BpmnModel bpmnModel, String processInstanceId, String activityId) {
    if (bpmnModel == null) {
      return null;
    }
    final FlowElement flowElement = bpmnModel.getFlowElement(activityId);
    if (!(flowElement instanceof Activity bpmnActivity)
        || !bpmnActivity.hasMultiInstanceLoopCharacteristics()) {
      return null;
    }
    for (final Execution execution :
        runtimeService.createExecutionQuery().processInstanceId(processInstanceId).list()) {
      if (!activityId.equals(execution.getActivityId())) {
        continue;
      }
      final Map<String, Object> local =
          runtimeService.getVariablesLocal(
              execution.getId(),
              List.of("nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances"));
      if (local.containsKey("nrOfInstances")) {
        return new MultiInstanceProgress(
            asInt(local.get("nrOfInstances")),
            asInt(local.get("nrOfActiveInstances")),
            asInt(local.get("nrOfCompletedInstances")));
      }
    }
    return null;
  }

  private static int asInt(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
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

  /**
   * Every historic update of every process variable, oldest first, capped to the most recent. Comes
   * back empty unless the engine's {@code flowable.history-level} is {@code full} -- one level
   * above Flowable's own default of {@code audit}, which already records the current value of every
   * variable ({@code HistoricVariableInstance}) but not the update-by-update audit trail ({@code
   * HistoricDetail}) this method reads.
   */
  private List<VariableHistoryEntry> variableHistory(String processInstanceId) {
    final List<HistoricDetail> details =
        historyService
            .createHistoricDetailQuery()
            .processInstanceId(processInstanceId)
            .variableUpdates()
            .orderByTime()
            .asc()
            .list();
    final int fromIndex = Math.max(0, details.size() - maxVariableHistoryEntries);
    return details.subList(fromIndex, details.size()).stream()
        .map(HistoricVariableUpdate.class::cast)
        .map(
            update ->
                new VariableHistoryEntry(
                    update.getVariableName(),
                    renderValue(update.getVariableName(), update.getValue()),
                    toInstant(update.getTime()),
                    update.getRevision()))
        .toList();
  }

  /**
   * Reconstructs, for every gateway transition actually taken, the full set of outgoing flows the
   * gateway could have taken. Flowable already records a {@code sequenceFlow}-type {@code
   * HistoricActivityInstance} for every transition -- {@link #SEQUENCE_FLOW_ACTIVITY_TYPE},
   * filtered out of {@link #activityTrail} as noise -- whose {@code activityId} is the taken
   * sequence flow's BPMN element ID, so no heuristic guessing at "which path was taken" is needed:
   * the taken flow is looked up directly, and its {@code sourceRef} checked against the deployed
   * {@link BpmnModel} for whether it's a gateway at all.
   */
  private List<GatewayTraceEntry> gatewayTrace(
      String processInstanceId, String processDefinitionId) {
    final BpmnModel bpmnModel = bpmnModel(processDefinitionId);
    if (bpmnModel == null) {
      return List.of();
    }
    final List<GatewayTraceEntry> trace = new ArrayList<>();
    for (final HistoricActivityInstance transition :
        historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(processInstanceId)
            .orderByHistoricActivityInstanceStartTime()
            .asc()
            .list()) {
      if (!SEQUENCE_FLOW_ACTIVITY_TYPE.equals(transition.getActivityType())) {
        continue;
      }
      if (!(bpmnModel.getFlowElement(transition.getActivityId())
          instanceof SequenceFlow takenFlow)) {
        continue;
      }
      if (!(bpmnModel.getFlowElement(takenFlow.getSourceRef()) instanceof Gateway gateway)) {
        continue;
      }
      trace.add(
          new GatewayTraceEntry(
              gateway.getId(),
              gateway.getName(),
              gateway.getClass().getSimpleName(),
              toInstant(transition.getStartTime()),
              gateway.getOutgoingFlows().stream()
                  .map(
                      outgoing ->
                          new GatewayOutgoingFlow(
                              outgoing.getId(),
                              outgoing.getTargetRef(),
                              targetActivityName(bpmnModel, outgoing.getTargetRef()),
                              outgoing.getConditionExpression(),
                              outgoing.getId().equals(takenFlow.getId())))
                  .toList()));
    }
    final int fromIndex = Math.max(0, trace.size() - maxActivityTrailEntries);
    return trace.subList(fromIndex, trace.size());
  }

  private static String targetActivityName(BpmnModel bpmnModel, String targetActivityId) {
    final FlowElement target = bpmnModel.getFlowElement(targetActivityId);
    return target != null && target.getName() != null ? target.getName() : targetActivityId;
  }

  /**
   * Jobs still waiting to run: timers not yet due (a separate table/query in Flowable until their
   * due date arrives) and async/message jobs already due but not yet picked up -- as opposed to
   * {@link #failedJobs}, which have already exhausted their retries.
   */
  private List<PendingJobInfo> pendingJobs(String processInstanceId) {
    return Stream.concat(
            managementService
                .createTimerJobQuery()
                .processInstanceId(processInstanceId)
                .list()
                .stream(),
            managementService.createJobQuery().processInstanceId(processInstanceId).list().stream())
        .map(this::toPendingJobInfo)
        .toList();
  }

  private PendingJobInfo toPendingJobInfo(Job job) {
    return new PendingJobInfo(
        job.getId(),
        job.getElementId(),
        job.getElementName(),
        job.getJobType(),
        toInstant(job.getDuedate()),
        job.getRetries());
  }

  private List<PendingTaskInfo> pendingTasks(String processInstanceId) {
    return taskService.createTaskQuery().processInstanceId(processInstanceId).list().stream()
        .map(this::toPendingTaskInfo)
        .toList();
  }

  private PendingTaskInfo toPendingTaskInfo(Task task) {
    final List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
    final List<String> candidateGroups =
        links.stream()
            .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType()))
            .map(IdentityLink::getGroupId)
            .filter(Objects::nonNull)
            .toList();
    final List<IdentityLinkInfo> identityLinks =
        links.stream()
            .map(link -> new IdentityLinkInfo(link.getType(), link.getUserId(), link.getGroupId()))
            .toList();
    return new PendingTaskInfo(
        task.getId(),
        task.getName(),
        task.getAssignee(),
        candidateGroups,
        toInstant(task.getDueDate()),
        task.getPriority(),
        identityLinks);
  }

  private List<CompletedTaskInfo> completedTasks(String processInstanceId) {
    return historyService
        .createHistoricTaskInstanceQuery()
        .processInstanceId(processInstanceId)
        .finished()
        .list()
        .stream()
        .map(
            task ->
                new CompletedTaskInfo(
                    task.getId(),
                    task.getName(),
                    task.getAssignee(),
                    task.getDurationInMillis(),
                    task.getDeleteReason()))
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
