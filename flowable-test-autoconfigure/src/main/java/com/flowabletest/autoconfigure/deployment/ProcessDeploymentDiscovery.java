package com.flowabletest.autoconfigure.deployment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Resolves an explicitly declared list of process names against the {@code root/<name>.bpmn20.xml}
 * convention -- the one-file-per-process convention already used throughout this starter and its
 * example app. Invoked by {@link FlowableTestProcessDeploymentEnvironmentPostProcessor}.
 *
 * <p>Unlike the HTTP-mock discovery this mirrors, there is no classpath-scan default here: {@code
 * flowable.test.processes.deploy} absent means Flowable's own {@code checkProcessDefinitions} scan
 * handles deployment exactly as it does today, so this class only ever runs once a consumer has
 * explicitly declared names.
 */
public final class ProcessDeploymentDiscovery {

  private final ResourcePatternResolver resourcePatternResolver;

  public ProcessDeploymentDiscovery(ResourcePatternResolver resourcePatternResolver) {
    this.resourcePatternResolver = resourcePatternResolver;
  }

  /**
   * Fails fast -- for every declared name, not just the ones that happen to resolve -- when its
   * BPMN file doesn't exist, so a missing or misspelled process is caught before the {@code
   * ApplicationContext} even starts refreshing, with an error naming exactly which declared process
   * is missing.
   *
   * @return process name -> classpath location (without a "classpath:" prefix), in declaration
   *     order
   */
  public Map<String, String> resolveDeclaredProcesses(String root, List<String> declaredNames) {
    final String rawRoot = stripClasspathPrefix(root);
    final Map<String, String> processes = new LinkedHashMap<>();
    for (final String name : declaredNames) {
      final String location = rawRoot + "/" + name + ".bpmn20.xml";
      final Resource resource = resourcePatternResolver.getResource("classpath:" + location);
      if (!resource.exists()) {
        throw new IllegalStateException(
            "flowable.test.processes.deploy declares '"
                + name
                + "' but no BPMN file was found at classpath:"
                + location
                + " -- check for a typo in `deploy`, that the file exists under "
                + "src/test/resources (or src/main/resources), and that '"
                + name
                + "' is the BPMN *file* name (e.g. 'order-processing'), not the process id "
                + "declared inside it (e.g. 'orderProcessing').");
      }
      processes.put(name, location);
    }
    return Map.copyOf(processes);
  }

  static String stripClasspathPrefix(String root) {
    String stripped = root;
    if (stripped.startsWith("classpath*:")) {
      stripped = stripped.substring("classpath*:".length());
    } else if (stripped.startsWith("classpath:")) {
      stripped = stripped.substring("classpath:".length());
    }
    while (stripped.startsWith("/")) {
      stripped = stripped.substring(1);
    }
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }
}
