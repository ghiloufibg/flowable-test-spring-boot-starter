package com.flowabletest.autoconfigure.assertions;

import com.flowabletest.autoconfigure.diagnostics.FlowableTestDiagnosticsAutoConfiguration;
import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.harness.ProcessTestHarness;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers a {@link ProcessTestHarness} bean wrapping the consumer's own Flowable engine service
 * beans. Activates once a {@code ProcessEngine} bean exists and {@code RuntimeService} is on the
 * classpath, and backs off if the consumer already defines its own {@code ProcessTestHarness}
 * bean; unlike the Kafka and HTTP-mocking capabilities, there is no optional third-party
 * dependency to gate on here.
 *
 * <p>Runs {@code afterName} both Flowable's own process-engine autoconfigurations and {@link
 * FlowableTestDiagnosticsAutoConfiguration}, so that when diagnostics is enabled its {@code
 * ProcessDiagnosticsCollector} bean already exists by the time {@link #processTestHarness} runs.
 * That collector is resolved defensively via {@link ObjectProvider#getIfAvailable()}, so this
 * configuration behaves identically whether or not diagnostics is enabled.
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
      RepositoryService repositoryService,
      Environment environment,
      ObjectProvider<ProcessDiagnosticsCollector> diagnosticsCollector) {
    return new ProcessTestHarness(
        runtimeService,
        taskService,
        historyService,
        managementService,
        repositoryService,
        environment.getProperty("flowable.test.processes.root", "classpath:processes"),
        diagnosticsCollector.getIfAvailable());
  }
}
