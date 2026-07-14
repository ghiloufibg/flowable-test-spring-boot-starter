package com.flowabletest.core.diagnostics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A point-in-time snapshot of one process instance's BPMN state, collected for attaching to
 * test-failure output. Deliberately domain-blind: every field is a generic Flowable concept
 * (activity ID, variable name, candidate group, job ID), never a project-specific one.
 */
public record ProcessDiagnosticsReport(
    String processInstanceId,
    String processDefinitionKey,
    Integer processDefinitionVersion,
    String businessKey,
    boolean active,
    List<ActivityInfo> currentActivities,
    Map<String, String> variables,
    List<ActivityTrailEntry> activityTrail,
    List<PendingTaskInfo> pendingTasks,
    List<FailedJobInfo> failedJobs) {

  public record ActivityInfo(String activityId, String activityName, String activityType) {}

  public record ActivityTrailEntry(
      String activityId, String activityName, Instant startTime, Instant endTime) {}

  public record PendingTaskInfo(
      String taskId, String name, String assignee, List<String> candidateGroups) {}

  public record FailedJobInfo(
      String jobId,
      String elementId,
      String exceptionMessage,
      String exceptionStacktrace,
      int retries) {}
}
