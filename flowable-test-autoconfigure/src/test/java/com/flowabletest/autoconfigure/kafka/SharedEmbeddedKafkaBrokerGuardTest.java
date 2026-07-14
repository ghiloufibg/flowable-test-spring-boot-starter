package com.flowabletest.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Unit-level proof of {@link SharedEmbeddedKafkaBrokerGuard}'s one job: {@code destroy()} must
 * never reach the wrapped delegate, while every other {@link EmbeddedKafkaBroker} method must.
 * {@link FlowableTestKafkaAutoConfigurationTest} and {@link FlowableKafkaBrokerScopePerContextTest}
 * cover the integration-level "which scope actually gets wrapped" question this class deliberately
 * stays out of.
 */
class SharedEmbeddedKafkaBrokerGuardTest {

  @Test
  void destroyNeverReachesTheDelegate() {
    final EmbeddedKafkaBroker delegate = mock(EmbeddedKafkaBroker.class);
    final EmbeddedKafkaBroker guarded = SharedEmbeddedKafkaBrokerGuard.suppressDestroy(delegate);

    guarded.destroy();

    verify(delegate, never()).destroy();
  }

  @Test
  void everyOtherMethodIsDelegatedUnchanged() {
    final EmbeddedKafkaBroker delegate = mock(EmbeddedKafkaBroker.class);
    when(delegate.getBrokersAsString()).thenReturn("localhost:9092");
    final EmbeddedKafkaBroker guarded = SharedEmbeddedKafkaBrokerGuard.suppressDestroy(delegate);

    assertThat(guarded.getBrokersAsString()).isEqualTo("localhost:9092");
    verify(delegate).getBrokersAsString();
  }

  @Test
  void returnedInstanceIsADynamicProxy() {
    final EmbeddedKafkaBroker delegate = mock(EmbeddedKafkaBroker.class);
    final EmbeddedKafkaBroker guarded = SharedEmbeddedKafkaBrokerGuard.suppressDestroy(delegate);

    assertThat(Proxy.isProxyClass(guarded.getClass())).isTrue();
  }
}
