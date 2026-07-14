package com.flowabletest.autoconfigure.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContextManager;

/**
 * Proves {@code @FlowableProcessTest(processes = {...})} deploys the declared process, and that
 * {@code enableDuplicateFiltering()} keeps a repeated {@code beforeTestClass} call (as happens
 * whenever Spring reuses a cached context across multiple classes with the same {@code
 * processes()}) idempotent rather than creating a new process-definition version each time.
 *
 * <p>Uses a dedicated {@code processes-annotation-test} root, never scanned by Flowable's own
 * default {@code classpath*:/processes/} location, so this test's deployment can't be confused with
 * anything the default scan (or Layer 1's allow-list) already deployed.
 */
class FlowableProcessAnnotationDeploymentTestExecutionListenerTest {

  @Test
  void deploysTheAnnotationDeclaredProcessIdempotentlyAcrossRepeatedBeforeTestClassCalls()
      throws Exception {
    final TestContextManager testContextManager =
        new TestContextManager(AnnotationDeployTargetApp.class);

    testContextManager.beforeTestClass();
    testContextManager.beforeTestClass();

    final RepositoryService repositoryService =
        testContextManager
            .getTestContext()
            .getApplicationContext()
            .getBean(RepositoryService.class);

    assertThat(
            repositoryService
                .createProcessDefinitionQuery()
                .processDefinitionKey("annotationDeclaredProcess")
                .count())
        .isEqualTo(1);
  }

  @FlowableProcessTest(
      classes = SampleFlowableApplication.class,
      properties = "flowable.test.processes.root=classpath:processes-annotation-test",
      processes = {"annotation-declared"})
  static class AnnotationDeployTargetApp {}
}
