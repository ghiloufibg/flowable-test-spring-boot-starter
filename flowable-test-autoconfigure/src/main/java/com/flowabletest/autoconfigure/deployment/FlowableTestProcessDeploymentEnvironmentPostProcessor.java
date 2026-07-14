package com.flowabletest.autoconfigure.deployment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

/**
 * {@code flowable.test.processes.deploy} (optional, opt-in) declares the authoritative default set
 * of BPMN processes to deploy, replacing Flowable's own {@code checkProcessDefinitions} classpath
 * scan. Absent (the default), behavior is unchanged: Flowable's own scan deploys every file under
 * {@code processDefinitionLocationPrefix}/{@code -Suffixes} exactly as today.
 *
 * <p>When {@code deploy} is declared, each name is resolved and validated -- fail-fast, before the
 * {@code ApplicationContext} refreshes -- against {@code
 * <flowable.test.processes.root>/<name>.bpmn20.xml} (default root {@code classpath:processes}), and
 * {@code flowable.check-process-definitions=false} is injected via {@code addFirst} (winning even
 * over a consumer's own {@code application.yml}) so this starter's allow-list becomes the sole
 * mechanism deciding the default deployment set, rather than racing Flowable's own scan for every
 * file it can still find under the same root. Each declared name is the BPMN <b>file</b> name (e.g.
 * {@code "order-processing"}), not the {@code <process id="...">} declared inside it (e.g. {@code
 * "orderProcessing"}) -- the two commonly differ by hyphenation.
 *
 * <p>The resolved {@code name -> classpathLocation} map is stashed as an {@code Environment}
 * property (see {@link ProcessDeploymentRegistry}) for {@link
 * FlowableTestProcessDeploymentAutoConfiguration}'s {@code SmartInitializingSingleton} to read once
 * {@code RepositoryService} exists -- deployment itself can't happen here, this runs before any
 * bean exists.
 */
public final class FlowableTestProcessDeploymentEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  private static final String PROPERTY_SOURCE_NAME = "flowableTestProcessDeployment";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
      return;
    }
    if (!classPresent("org.flowable.engine.RuntimeService")) {
      return;
    }

    final List<String> declaredProcesses =
        Binder.get(environment)
            .bind("flowable.test.processes.deploy", Bindable.listOf(String.class))
            .orElse(List.of());
    if (declaredProcesses.isEmpty()) {
      return;
    }

    final String root =
        environment.getProperty("flowable.test.processes.root", "classpath:processes");
    final ProcessDeploymentDiscovery discovery =
        new ProcessDeploymentDiscovery(new PathMatchingResourcePatternResolver());
    final Map<String, String> processes =
        discovery.resolveDeclaredProcesses(root, declaredProcesses);

    final Map<String, Object> properties = new HashMap<>();
    properties.put("flowable.check-process-definitions", false);
    properties.put(
        "flowable.test.processes.discovered", ProcessDeploymentRegistry.encode(processes));

    environment
        .getPropertySources()
        .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  private static boolean classPresent(String className) {
    return ClassUtils.isPresent(
        className, FlowableTestProcessDeploymentEnvironmentPostProcessor.class.getClassLoader());
  }
}
