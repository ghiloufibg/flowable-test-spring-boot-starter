package com.flowabletest.autoconfigure.assertions;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ProcessTestHarness} and {@code @FlowableProcessTest} work end to end against a
 * real Flowable engine (pinned test-scope dependency; never leaks to consumers -- design doc
 * section 5.5) and a real, if tiny, BPMN process. This is the internal validation for design doc
 * section 4.4/4.5, plus an implicit check that
 * {@code FlowableCompatibilityGuardAutoConfiguration} doesn't reject a supported (7.1.0) engine:
 * if it did, the context below would fail to start.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ProcessTestHarnessTest {

    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RuntimeService runtimeService;
    @Autowired
    ProcessTestHarness harness;

    @BeforeEach
    void deployHelloProcess() {
        if (repositoryService.createProcessDefinitionQuery().processDefinitionKey("helloProcess").count() == 0) {
            repositoryService.createDeployment()
                    .addClasspathResource("processes/hello.bpmn20.xml")
                    .deploy();
        }
    }

    @Test
    void completingTheSingleTaskEndsTheProcess() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

        harness.assertThat(instance.getId()).isActive();

        Task task = harness.completeSingleTask(instance.getId(), "reviewers", Map.of());
        assertThat(task.getName()).isEqualTo("Review");

        harness.assertThat(instance.getId()).hasEndedAt("endEvent");
    }

    @Test
    void awaitTaskForCandidateGroupPollsUntilTheTaskAppears() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloProcess");

        Task task = harness.awaitTaskForCandidateGroup(instance.getId(), "reviewers", Duration.ofSeconds(5));

        assertThat(task.getName()).isEqualTo("Review");
    }
}
