package com.flowabletest.autoconfigure.deployment;

import com.flowabletest.core.annotation.FlowableProcessTest;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.DeploymentBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.util.ClassUtils;

/**
 * Layer 2 (annotation) of the process-deployment mechanism: deploys
 * {@code @FlowableProcessTest(processes = {...})} on top of whatever Layer 1's default allow-list
 * already deployed. Has to be a {@code TestExecutionListener}, not a {@code
 * ContextCustomizerFactory}: {@code ContextCustomizer.customizeContext} runs before {@code
 * refresh()}, when {@code RepositoryService} doesn't exist yet, and {@code processes()} isn't one
 * of the annotation attributes Spring's {@code MergedContextConfiguration} considers for cache-key
 * purposes anyway -- it only needs bean access at the right lifecycle point, not a cache-key
 * contribution (contrast with {@code isolation()}, read by {@code
 * StrictIsolationContextCustomizerFactory} precisely because it does need to affect the cache key).
 *
 * <p>{@code enableDuplicateFiltering()} is load-bearing, not optional: {@link #beforeTestClass}
 * fires every time, including every time Spring reuses a cached context across multiple classes
 * declaring the same {@code processes()} -- without it, each reuse would create a brand-new
 * deployment and process-definition version for byte-identical content.
 *
 * <p>Registered via {@code META-INF/spring.factories} under {@code
 * org.springframework.test.context.TestExecutionListener}, which means it is unconditionally
 * instantiated for every test using the Spring TestContext framework, including consumers with no
 * Flowable on their classpath at all. {@link #classPresent} guards against that, checked before any
 * method referencing a Flowable-typed signature runs -- same classpath-guard discipline already
 * used by {@code FlowableKafkaConsumerLifecycleTestExecutionListener}.
 */
public final class FlowableProcessAnnotationDeploymentTestExecutionListener
    implements TestExecutionListener {

  @Override
  public void beforeTestClass(TestContext testContext) {
    if (!classPresent("org.flowable.engine.RepositoryService")) {
      return;
    }
    final FlowableProcessTest annotation =
        AnnotatedElementUtils.findMergedAnnotation(
            testContext.getTestClass(), FlowableProcessTest.class);
    if (annotation == null || annotation.processes().length == 0) {
      return;
    }
    deployAnnotationDeclaredProcesses(testContext, annotation);
  }

  private void deployAnnotationDeclaredProcesses(
      TestContext testContext, FlowableProcessTest annotation) {
    final ApplicationContext context = testContext.getApplicationContext();
    final Environment environment = context.getEnvironment();
    final String root =
        ProcessDeploymentDiscovery.stripClasspathPrefix(
            environment.getProperty("flowable.test.processes.root", "classpath:processes"));
    final RepositoryService repositoryService = context.getBean(RepositoryService.class);

    final DeploymentBuilder deploymentBuilder =
        repositoryService
            .createDeployment()
            .name("@FlowableProcessTest.processes=" + String.join(",", annotation.processes()))
            .enableDuplicateFiltering();
    for (final String processName : annotation.processes()) {
      deploymentBuilder.addClasspathResource(root + "/" + processName + ".bpmn20.xml");
    }
    deploymentBuilder.deploy();
  }

  private static boolean classPresent(String className) {
    return ClassUtils.isPresent(
        className, FlowableProcessAnnotationDeploymentTestExecutionListener.class.getClassLoader());
  }
}
