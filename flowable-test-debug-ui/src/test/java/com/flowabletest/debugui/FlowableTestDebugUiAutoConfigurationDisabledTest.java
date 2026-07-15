package com.flowabletest.debugui;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.debugui.testapp.SampleFlowableApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Proves the debug UI stays off by default -- {@code flowable.test.debug-ui.enabled=false}
 * overrides this module's own {@code application.yml}, which otherwise sets it {@code true} for
 * every other test in this module. Uses its own Spring context (distinct property value from {@link
 * FlowableTestDebugUiAutoConfigurationEnabledTest}), same pattern as {@code
 * ProcessInstanceTrackerTrackingLimitTest} in {@code flowable-test-autoconfigure}.
 */
@FlowableProcessTest(
    classes = SampleFlowableApplication.class,
    properties = "flowable.test.debug-ui.enabled=false")
class FlowableTestDebugUiAutoConfigurationDisabledTest {

  @Autowired ApplicationContext applicationContext;

  @Test
  void doesNotRegisterTheDebugUiServerWhenDisabled() {
    assertThat(applicationContext.getBeanNamesForType(DebugUiServer.class)).isEmpty();
  }
}
