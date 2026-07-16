package com.flowabletest.core.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A point-in-time snapshot of one process instance's BPMN state, produced by {@link
 * ProcessDiagnosticsCollector} and rendered by {@link ProcessDiagnosticsFormatter} for attaching to
 * test-failure output. Deliberately domain-blind: every field is a generic Flowable concept
 * (activity ID, variable name, candidate group, job ID), never a project-specific one -- except
 * {@link #testOrigin()}, which is the one field that names a test, and only ever {@code null} or a
 * {@code SimpleClassName.methodName} string, never anything project-specific either.
 */
public record ProcessDiagnosticsReport(
    String processInstanceId,
    String processDefinitionKey,
    Integer processDefinitionVersion,
    String processDefinitionName,
    String processDefinitionCategory,
    String deploymentId,
    String bpmnResourceName,
    Instant deploymentTime,
    String businessKey,
    String superProcessInstanceId,
    boolean active,
    List<ActivityInfo> currentActivities,
    Map<String, String> variables,
    List<VariableHistoryEntry> variableHistory,
    List<ActivityTrailEntry> activityTrail,
    List<GatewayTraceEntry> gatewayTrace,
    List<PendingTaskInfo> pendingTasks,
    List<CompletedTaskInfo> completedTasks,
    List<FailedJobInfo> failedJobs,
    List<PendingJobInfo> pendingJobs,
    String testOrigin) {

  public record ActivityInfo(
      String activityId,
      String activityName,
      String activityType,
      MultiInstanceProgress multiInstanceProgress,
      String calledProcessInstanceId) {}

  /**
   * Loop counters Flowable maintains as local variables on a multi-instance activity's scope
   * execution. {@code null} on {@link ActivityInfo#multiInstanceProgress()} for any activity that
   * isn't multi-instance.
   */
  public record MultiInstanceProgress(
      int nrOfInstances, int nrOfActiveInstances, int nrOfCompletedInstances) {}

  public record ActivityTrailEntry(
      String activityId, String activityName, Instant startTime, Instant endTime) {}

  /**
   * One historic update of a single process variable, oldest first. Requires the engine's {@code
   * flowable.history-level} to be {@code full} -- comes back empty at Flowable's own default of
   * {@code audit}.
   */
  public record VariableHistoryEntry(
      String variableName, String value, Instant time, int revision) {}

  /**
   * One gateway transition actually taken during this instance's execution, alongside every
   * outgoing flow the gateway could have taken -- reconstructed from the {@code sequenceFlow}-type
   * {@code HistoricActivityInstance} entries Flowable already records for every transition, matched
   * back against the deployed BPMN model.
   */
  public record GatewayTraceEntry(
      String gatewayId,
      String gatewayName,
      String gatewayType,
      Instant time,
      List<GatewayOutgoingFlow> outgoingFlows) {}

  public record GatewayOutgoingFlow(
      String sequenceFlowId,
      String targetActivityId,
      String targetActivityName,
      String conditionExpression,
      boolean taken) {}

  public record PendingTaskInfo(
      String taskId,
      String name,
      String assignee,
      List<String> candidateGroups,
      Instant dueDate,
      int priority,
      List<IdentityLinkInfo> identityLinks) {}

  public record CompletedTaskInfo(
      String taskId, String name, String assignee, Long durationMillis, String deleteReason) {}

  /** One {@code IdentityLinkType} entry (assignee, candidate, owner, participant, ...). */
  public record IdentityLinkInfo(String type, String userId, String groupId) {}

  public record FailedJobInfo(
      String jobId,
      String elementId,
      String exceptionMessage,
      String exceptionStacktrace,
      int retries) {}

  /**
   * A job still waiting to run -- either a timer not yet due, or an async/message job already due
   * but not yet picked up -- as opposed to {@link FailedJobInfo}, which has already exhausted its
   * retries and been moved to the dead-letter table.
   */
  public record PendingJobInfo(
      String jobId,
      String elementId,
      String elementName,
      String jobType,
      Instant dueDate,
      int retries) {}
}
