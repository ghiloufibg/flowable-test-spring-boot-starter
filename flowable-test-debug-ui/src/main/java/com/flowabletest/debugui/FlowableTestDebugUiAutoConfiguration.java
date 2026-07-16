package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector;
import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Registers the opt-in BPMN debug UI: a read-only, localhost-only HTTP server showing the current
 * diagram (with active activities highlighted), variables, pending tasks, and activity trail for
 * process instances the diagnostics capability is already tracking.
 *
 * <p>Off by default -- activates only once {@code flowable.test.debug-ui.enabled=true} is set
 * explicitly, and backs off entirely if the diagnostics capability itself is disabled, since this
 * is built entirely on {@link ProcessDiagnosticsCollector}/{@link ProcessInstanceTracker} rather
 * than querying the engine itself.
 */
@AutoConfiguration(
    afterName = {
      "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
      "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration",
      "com.flowabletest.autoconfigure.diagnostics.FlowableTestDiagnosticsAutoConfiguration"
    })
@ConditionalOnBean({
  ProcessEngine.class,
  ProcessDiagnosticsCollector.class,
  ProcessInstanceTracker.class
})
@ConditionalOnProperty(prefix = "flowable.test.debug-ui", name = "enabled", matchIfMissing = false)
@EnableConfigurationProperties(DebugUiProperties.class)
public class FlowableTestDebugUiAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DebugUiServer debugUiServer(
      ProcessEngine processEngine,
      RuntimeService runtimeService,
      RepositoryService repositoryService,
      HistoryService historyService,
      ProcessDiagnosticsCollector processDiagnosticsCollector,
      ProcessInstanceTracker processInstanceTracker,
      DebugUiProperties properties,
      ApplicationContext applicationContext) {
    final ProcessInstanceDiagramRenderer diagramRenderer =
        new ProcessInstanceDiagramRenderer(
            processEngine, runtimeService, repositoryService, historyService);
    return new DebugUiServer(
        properties,
        new InstanceListHandler(processInstanceTracker, processDiagnosticsCollector),
        new InstanceDetailHandler(processDiagnosticsCollector),
        new DiagramImageHandler(diagramRenderer),
        new DiagnosticsTextHandler(processDiagnosticsCollector),
        new StaticResourceHandler(),
        applicationContext.getId());
  }
}
