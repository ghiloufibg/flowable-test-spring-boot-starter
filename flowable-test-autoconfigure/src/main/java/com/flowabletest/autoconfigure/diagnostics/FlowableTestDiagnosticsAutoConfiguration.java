package com.flowabletest.autoconfigure.diagnostics;

import com.flowabletest.core.diagnostics.FlowableProcessDiagnosticsExtension;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the BPMN test-failure diagnostics capability: a {@link ProcessInstanceTracker}
 * subscribed to the engine's {@code PROCESS_STARTED} events, and a {@link
 * ProcessDiagnosticsCollector} that turns a process instance ID into a full snapshot (current
 * activity, variables, activity trail, pending tasks, dead-letter job failures).
 *
 * <p>Activates once a {@code ProcessEngine} bean exists, unless {@code
 * flowable.test.diagnostics.enabled} is set to {@code false} -- for consumers who have their own
 * failure-reporting tooling and consider this noise. Both beans back off individually via
 * {@code @ConditionalOnMissingBean} if the consumer already defines one. {@link
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
@EnableConfigurationProperties(FlowableTestDiagnosticsProperties.class)
public class FlowableTestDiagnosticsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessInstanceTracker processInstanceTracker(
      ProcessEngine processEngine, FlowableTestDiagnosticsProperties properties) {
    final ProcessInstanceTracker tracker =
        new ProcessInstanceTracker(properties.maxTrackedProcessInstances());
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
      RepositoryService repositoryService,
      FlowableTestDiagnosticsProperties properties) {
    return new ProcessDiagnosticsCollector(
        runtimeService,
        taskService,
        historyService,
        managementService,
        repositoryService,
        properties.maxActivityTrailEntries(),
        properties.maxVariableValueLength(),
        properties.includeFailedJobs(),
        properties.redactedVariableNames());
  }
}
