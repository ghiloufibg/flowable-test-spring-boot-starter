package com.flowabletest.autoconfigure.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.FlowableTestIsolation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextCustomizer;

class StrictIsolationContextCustomizerFactoryTest {

  private final StrictIsolationContextCustomizerFactory factory =
      new StrictIsolationContextCustomizerFactory();

  @FlowableProcessTest
  private static class SharedByDefaultTestClass {}

  @FlowableProcessTest(isolation = FlowableTestIsolation.SEPARATE_CONTEXT)
  private static class SeparateContextTestClassA {}

  @FlowableProcessTest(isolation = FlowableTestIsolation.SEPARATE_CONTEXT)
  private static class SeparateContextTestClassB {}

  @Test
  void returnsNoCustomizerForTheDefaultSharedIsolation() {
    assertThat(factory.createContextCustomizer(SharedByDefaultTestClass.class, List.of())).isNull();
  }

  @Test
  void returnsACustomizerForSeparateContextIsolation() {
    assertThat(factory.createContextCustomizer(SeparateContextTestClassA.class, List.of()))
        .isNotNull();
  }

  @Test
  void twoDifferentSeparateContextClassesNeverProduceAnEqualCacheKey() {
    final ContextCustomizer a =
        factory.createContextCustomizer(SeparateContextTestClassA.class, List.of());
    final ContextCustomizer b =
        factory.createContextCustomizer(SeparateContextTestClassB.class, List.of());

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void theSameClassAlwaysProducesAnEqualCacheKey() {
    final ContextCustomizer first =
        factory.createContextCustomizer(SeparateContextTestClassA.class, List.of());
    final ContextCustomizer second =
        factory.createContextCustomizer(SeparateContextTestClassA.class, List.of());

    assertThat(first).isEqualTo(second);
  }
}
