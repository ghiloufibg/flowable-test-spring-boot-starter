package com.flowabletest.debugui;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.debugui.testapp.SampleFlowableApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@code flowable.test.debug-ui.enabled=true} (set in this module's own {@code
 * application.yml}) actually registers and starts a {@link DebugUiServer} bean.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class FlowableTestDebugUiAutoConfigurationEnabledTest {

  @Autowired DebugUiServer debugUiServer;

  @Test
  void registersAndStartsTheDebugUiServerWhenEnabled() {
    assertThat(debugUiServer).isNotNull();
    assertThat(debugUiServer.isRunning()).isTrue();
    assertThat(debugUiServer.port()).isPositive();
  }
}
