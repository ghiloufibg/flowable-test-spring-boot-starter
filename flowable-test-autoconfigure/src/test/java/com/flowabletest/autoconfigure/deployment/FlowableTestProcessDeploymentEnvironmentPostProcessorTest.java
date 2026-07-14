package com.flowabletest.autoconfigure.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves {@code flowable.test.processes.deploy}, driving {@link SpringApplicationBuilder} directly
 * rather than {@code @FlowableProcessTest} or {@code ApplicationContextRunner} -- same rationale as
 * {@code FlowableTestHttpStubServicesAllowListTest}: the property is read by an {@code
 * EnvironmentPostProcessor}, which {@code ApplicationContextRunner} bypasses entirely.
 *
 * <p>Uses a dedicated {@code processes-deploy-test} root (two files, {@code declared-process} and
 * {@code undeclared-process}), never scanned by Flowable's own default {@code
 * classpath*:/processes/} location, so the "absent" case can assert neither file was deployed at
 * all -- proving the default classpath scan really did leave this root alone -- and the "declared"
 * case can assert this module's own {@code processes/hello.bpmn20.xml} fixture (scanned by
 * Flowable's default location) was *not* auto-deployed, proving the allow-list disables that scan.
 */
class FlowableTestProcessDeploymentEnvironmentPostProcessorTest {

  private static final String ROOT = "classpath:processes-deploy-test";

  @Test
  void deployAbsent_leavesBothFilesUndeployed() {
    try (ConfigurableApplicationContext context = startContext(ROOT)) {
      final RepositoryService repositoryService = context.getBean(RepositoryService.class);

      assertThat(processDefinitionCount(repositoryService, "declaredProcess")).isZero();
      assertThat(processDefinitionCount(repositoryService, "undeclaredProcess")).isZero();
    }
  }

  @Test
  void deployDeclared_onlyDeploysDeclaredNamesAndDisablesFlowablesOwnScan() {
    try (ConfigurableApplicationContext context =
        startContext(ROOT, "flowable.test.processes.deploy=declared-process")) {
      final RepositoryService repositoryService = context.getBean(RepositoryService.class);

      assertThat(processDefinitionCount(repositoryService, "declaredProcess")).isEqualTo(1);
      assertThat(processDefinitionCount(repositoryService, "undeclaredProcess")).isZero();
      assertThat(processDefinitionCount(repositoryService, "helloProcess")).isZero();
    }
  }

  @Test
  void deployDeclared_missingBpmnFileFailsFastBeforeContextRefresh() {
    assertThatThrownBy(
            () ->
                startContext(
                    ROOT, "flowable.test.processes.deploy=declared-process,missing-process"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing-process")
        .hasMessageContaining("processes-deploy-test/missing-process.bpmn20.xml");
  }

  private static long processDefinitionCount(RepositoryService repositoryService, String key) {
    return repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count();
  }

  private static ConfigurableApplicationContext startContext(String root, String... properties) {
    final SpringApplicationBuilder builder =
        new SpringApplicationBuilder(SampleFlowableApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "flowable.test.processes.root=" + root,
                "flowable.test.kafka.broker-scope=per-context");
    builder.properties(properties);
    return builder.run();
  }
}
