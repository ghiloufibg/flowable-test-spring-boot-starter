package com.flowabletest.autoconfigure.diagnostics;

import com.flowabletest.core.diagnostics.FlowableProcessDiagnosticsExtension;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import java.util.List;
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
 * activity, variables, activity trail, pending tasks, dead-letter job failures).
 *
 * <p>Activates once a {@code ProcessEngine} bean exists, unless {@code
 * flowable.test.diagnostics.enabled} is set to {@code false} -- for consumers who have their own
 * failure-reporting tooling and consider this noise. Both beans back off individually via {@code
 * @ConditionalOnMissingBean} if the consumer already defines one. {@link
 * FlowableProcessDiagnosticsExtension}, wired into {@code @FlowableProcessTest} directly, resolves
 * both beans defensively via {@code getIfAvailable()}, so disabling this auto-configuration (or a
 * context-refresh failure before it runs) simply means test failures go unenriched -- exactly as
 * before this capability existed.
 *
 * <p>Two properties bound here exist specifically so this capability holds up under real, not just
 * toy, usage: {@code flowable.test.diagnostics.redacted-variable-names} (default covers common
 * secret-ish names) keeps a process variable that happens to hold a password or token out of text
 * that routinely ends up archived in CI, and {@code
 * flowable.test.diagnostics.max-tracked-process-instances} caps how many process instances a single
 * failure will run full diagnostics queries against, so a test that starts an unusually large
 * number of instances can't turn one failure into an unbounded diagnostics-collection cost.
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
  ProcessInstanceTracker processInstanceTracker(
      ProcessEngine processEngine,
      @Value("${flowable.test.diagnostics.max-tracked-process-instances:50}")
          int maxTrackedProcessInstances) {
    final ProcessInstanceTracker tracker = new ProcessInstanceTracker(maxTrackedProcessInstances);
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
      @Value("${flowable.test.diagnostics.include-failed-jobs:true}") boolean includeFailedJobs,
      @Value(
              "${flowable.test.diagnostics.redacted-variable-names:password,token,secret,apikey,authorization,ssn}")
          List<String> redactedVariableNames) {
    return new ProcessDiagnosticsCollector(
        runtimeService,
        taskService,
        historyService,
        managementService,
        maxActivityTrailEntries,
        maxVariableValueLength,
        includeFailedJobs,
        redactedVariableNames);
  }
}
