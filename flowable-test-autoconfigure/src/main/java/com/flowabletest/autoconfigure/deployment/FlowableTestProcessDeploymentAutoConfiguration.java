package com.flowabletest.autoconfigure.deployment;

import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.DeploymentBuilder;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Deploys the default set of BPMN processes that {@link
 * FlowableTestProcessDeploymentEnvironmentPostProcessor} resolved from {@code
 * flowable.test.processes.deploy}, once a {@code ProcessEngine} bean exists and {@code
 * RuntimeService} is on the classpath.
 *
 * <p>{@link #defaultProcessDeployer} returns a {@link SmartInitializingSingleton}, which runs
 * automatically as part of context refresh with no test-framework dependency at all -- mirroring
 * how Flowable's own classpath auto-deployment also runs as part of context refresh rather than as
 * a JUnit hook, since this is a drop-in replacement for that mechanism.
 *
 * <p>A no-op when {@code flowable.test.processes.deploy} was absent: {@link
 * ProcessDeploymentRegistry#resolve} then returns an empty map, and the deployer does nothing
 * further, leaving Flowable's own classpath scan (left enabled in that case) as the sole deployer.
 */
@AutoConfiguration(
    afterName = {
      "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
      "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration"
    })
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
@ConditionalOnBean(ProcessEngine.class)
public class FlowableTestProcessDeploymentAutoConfiguration {

  @Bean
  SmartInitializingSingleton defaultProcessDeployer(
      RepositoryService repositoryService, Environment environment) {
    return () -> deployDefaultProcesses(repositoryService, environment);
  }

  private void deployDefaultProcesses(
      RepositoryService repositoryService, Environment environment) {
    final Map<String, String> processes = ProcessDeploymentRegistry.resolve(environment);
    if (processes.isEmpty()) {
      return;
    }
    final DeploymentBuilder deploymentBuilder =
        repositoryService
            .createDeployment()
            .name("flowable.test.processes.deploy=" + String.join(",", processes.keySet()))
            .enableDuplicateFiltering();
    processes.values().forEach(deploymentBuilder::addClasspathResource);
    deploymentBuilder.deploy();
  }
}
