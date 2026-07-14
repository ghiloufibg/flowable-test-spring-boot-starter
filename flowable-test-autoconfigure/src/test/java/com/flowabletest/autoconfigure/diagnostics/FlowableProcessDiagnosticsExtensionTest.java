package com.flowabletest.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.diagnostics.ProcessDiagnosticsAttachment;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Proves {@code FlowableProcessDiagnosticsExtension} -- wired automatically into every
 * {@code @FlowableProcessTest} via {@code @ExtendWith} -- actually attaches BPMN diagnostics to a
 * real test failure. Runs {@link DiagnosticsFailureFixture} (a test class that deliberately fails)
 * programmatically via {@code EngineTestKit} rather than letting it run as part of this module's
 * own Surefire execution, then inspects the resulting failure directly. Internal validation for
 * {@code claudedocs/bpmn-failure-diagnostics-design.md}.
 */
class FlowableProcessDiagnosticsExtensionTest {

  @Test
  void attachesBpmnDiagnosticsAsASuppressedExceptionOnTestFailure() {
    final EngineExecutionResults results =
        EngineTestKit.engine("junit-jupiter")
            .selectors(DiscoverySelectors.selectClass(DiagnosticsFailureFixture.class))
            .execute();

    results.testEvents().assertStatistics(stats -> stats.failed(1));

    final TestExecutionResult executionResult =
        results.testEvents().failed().stream()
            .findFirst()
            .orElseThrow()
            .getRequiredPayload(TestExecutionResult.class);
    final Throwable failure = executionResult.getThrowable().orElseThrow();

    assertThat(failure.getSuppressed())
        .anySatisfy(
            suppressed -> {
              assertThat(suppressed).isInstanceOf(ProcessDiagnosticsAttachment.class);
              assertThat(suppressed.getMessage())
                  .contains("diagnosticsProcess")
                  .contains("reviewTask");
            });
  }
}
