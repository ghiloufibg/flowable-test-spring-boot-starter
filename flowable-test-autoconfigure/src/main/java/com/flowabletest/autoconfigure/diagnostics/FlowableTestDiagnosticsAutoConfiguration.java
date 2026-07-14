package com.flowabletest.autoconfigure.diagnostics;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers the BPMN test-failure diagnostics capability: a {@link ProcessInstanceTracker}
 * subscribed to the engine's {@code PROCESS_STARTED} events, and a {@link
 * ProcessDiagnosticsCollector} that turns a process instance ID into a full snapshot (current
 * activity, variables, activity trail, pending tasks, dead-letter job failures). {@link
 * com.flowabletest.core.diagnostics.FlowableProcessDiagnosticsExtension} (wired into
 * {@code @FlowableProcessTest} directly) resolves both beans defensively via {@code
 * getIfAvailable()}, so disabling this auto-configuration (or a context-refresh failure before it
 * runs) simply means test failures go unenriched -- exactly as before this capability existed.
 *
 * <p>Always active once a {@code ProcessEngine} exists, like {@code
 * FlowableTestAssertionsAutoConfiguration} -- no optional third-party dependency to gate on -- but
 * unlike that one, this capability can be switched off wholesale via {@code
 * flowable.test.diagnostics.enabled=false} for consumers who have their own failure-reporting
 * tooling and consider this noise.
 */
@AutoConfiguration(
    afterName = {
      "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
      "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration"
    })
@ConditionalOnBean(ProcessEngine.class)
@ConditionalOnProperty(
    prefix = "flowable.test.diagnostics",
    name = "enabled",
    matchIfMissing = true)
public class FlowableTestDiagnosticsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessInstanceTracker processInstanceTracker(ProcessEngine processEngine) {
    final ProcessInstanceTracker tracker = new ProcessInstanceTracker();
    processEngine
        .getProcessEngineConfiguration()
        .getEventDispatcher()
        .addEventListener(tracker, FlowableEngineEventType.PROCESS_STARTED);
    return tracker;
  }

  @Bean
  @ConditionalOnMissingBean
  ProcessDiagnosticsCollector processDiagnosticsCollector(
      RuntimeService runtimeService,
      TaskService taskService,
      HistoryService historyService,
      ManagementService managementService,
      @Value("${flowable.test.diagnostics.max-activity-trail-entries:20}")
          int maxActivityTrailEntries,
      @Value("${flowable.test.diagnostics.max-variable-value-length:500}")
          int maxVariableValueLength,
      @Value("${flowable.test.diagnostics.include-failed-jobs:true}") boolean includeFailedJobs) {
    return new ProcessDiagnosticsCollector(
        runtimeService,
        taskService,
        historyService,
        managementService,
        maxActivityTrailEntries,
        maxVariableValueLength,
        includeFailedJobs);
  }
}
