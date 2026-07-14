package com.flowabletest.autoconfigure.assertions;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.harness.ProcessTestHarness;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link ProcessTestHarness} around the consumer's own Flowable engine beans. Always
 * active once a {@code ProcessEngine} exists -- unlike Kafka/HTTP mocking, this capability has no
 * optional third-party dependency to gate on.
 *
 * <p>{@code afterName} additionally includes {@code FlowableTestDiagnosticsAutoConfiguration} so
 * that, when diagnostics is enabled, its {@code ProcessDiagnosticsCollector} bean is already
 * defined by the time this configuration processes -- resolved defensively via {@link
 * ObjectProvider#getIfAvailable()}, so this class works identically whether or not diagnostics is
 * enabled.
 */
@AutoConfiguration(
    afterName = {
      "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
      "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration",
      "com.flowabletest.autoconfigure.diagnostics.FlowableTestDiagnosticsAutoConfiguration"
    })
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
@ConditionalOnBean(ProcessEngine.class)
public class FlowableTestAssertionsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessTestHarness processTestHarness(
      RuntimeService runtimeService,
      TaskService taskService,
      HistoryService historyService,
      ManagementService managementService,
      ObjectProvider<ProcessDiagnosticsCollector> diagnosticsCollector) {
    return new ProcessTestHarness(
        runtimeService,
        taskService,
        historyService,
        managementService,
        diagnosticsCollector.getIfAvailable());
  }
}
