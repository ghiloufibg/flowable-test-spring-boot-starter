package com.flowabletest.autoconfigure.kafka;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.springframework.kafka.test.EmbeddedKafkaBroker;

/**
 * Wraps the JVM-wide shared {@link EmbeddedKafkaBroker} singleton in a dynamic proxy that turns
 * {@code destroy()} into a no-op before {@link
 * FlowableTestKafkaAutoConfiguration#embeddedKafkaBroker()} hands it to a Spring context.
 *
 * <p>{@code EmbeddedKafkaBroker} extends Spring's own {@code DisposableBean}, so
 * {@code @Bean(destroyMethod = "")} on that factory method is not enough by itself: that attribute
 * only disables destroy-method <em>inference</em>/named-method lookup, but Spring's {@code
 * DisposableBeanAdapter} additionally invokes the {@code DisposableBean} interface callback on any
 * bean that implements it, unconditionally, regardless of {@code destroyMethod}. Every Spring
 * context that autowires this shared broker would therefore call {@code destroy()} on it
 * independently when that context closes -- and {@link EmbeddedFlowableKafkaSupport}'s own JVM
 * shutdown hook calls it a further time at JVM exit, since it is the one true owner of this
 * singleton's lifecycle. {@code EmbeddedKafkaKraftBroker#destroy()} has no idempotency guard
 * (unlike its {@code afterPropertiesSet()}, which does), so a second or later call races an
 * already-torn-down {@code KafkaClusterTestKit}: a {@code RejectedExecutionException} from its
 * internal executor, then a {@code FileSystemException} deleting log segments already gone -- both
 * observed in practice, the second one specifically on Windows, where a half-deleted, still
 * memory-mapped log file turns the second close into a hard failure instead of a harmless no-op.
 *
 * <p>Every other interface method -- including {@code afterPropertiesSet()} -- is delegated
 * unchanged, so each context still observes the real broker's normal behavior.
 */
final class SharedEmbeddedKafkaBrokerGuard {

  private SharedEmbeddedKafkaBrokerGuard() {}

  static EmbeddedKafkaBroker suppressDestroy(EmbeddedKafkaBroker delegate) {
    return (EmbeddedKafkaBroker)
        Proxy.newProxyInstance(
            EmbeddedKafkaBroker.class.getClassLoader(),
            new Class<?>[] {EmbeddedKafkaBroker.class},
            (proxy, method, args) -> {
              if (isNoArgDestroy(method)) {
                return null;
              }
              try {
                return method.invoke(delegate, args);
              } catch (final InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  private static boolean isNoArgDestroy(Method method) {
    return "destroy".equals(method.getName()) && method.getParameterCount() == 0;
  }
}
