package com.flowabletest.autoconfigure.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;

/**
 * Guards against the compile-time-constant-inlining regression this class's own reflective read
 * exists to avoid: a direct {@code ProcessEngine.VERSION} field access would get inlined into this
 * module's own compiled bytecode at this module's build time instead of reading the consumer's
 * actual runtime engine version. {@link
 * FlowableCompatibilityGuardAutoConfiguration#resolveRuntimeEngineVersion()} must always equal
 * {@link ProcessEngine#VERSION} as loaded on the current classpath -- trivially true against this
 * module's own pinned test-scope engine, but the point of the reflective read is that it stays true
 * against whatever engine class is actually loaded, unlike a direct field reference.
 */
class FlowableCompatibilityGuardAutoConfigurationTest {

  @Test
  void resolveRuntimeEngineVersion_readsTheActualLoadedProcessEngineClassesVersionField() {
    assertThat(FlowableCompatibilityGuardAutoConfiguration.resolveRuntimeEngineVersion())
        .isEqualTo(ProcessEngine.VERSION);
  }

  @Test
  void flowableCompatibilityGuard_doesNotThrow_forTheCurrentlySupportedEngineVersion()
      throws Exception {
    final InitializingBean guard =
        new FlowableCompatibilityGuardAutoConfiguration()
            .flowableCompatibilityGuard(mock(ProcessEngine.class));

    assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
  }
}
