package com.flowabletest.autoconfigure.http;

import com.flowabletest.core.http.HttpMockServers;
import com.flowabletest.core.http.HttpStubConfigurer;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration that exposes the WireMock servers discovered by {@link
 * FlowableTestHttpStubEnvironmentPostProcessor} (or, for an overridden service, {@link
 * MockExternalServiceContextCustomizer}) as regular Spring beans. Activates when {@link
 * WireMockServer} and {@code org.flowable.engine.RuntimeService} are both on the classpath.
 *
 * <p>{@code httpMockServers} resolves this context's final, override-applied service map via
 * {@link HttpMockServiceRegistry} and retains each entry, so a context with a {@code
 * @MockExternalService} override always sees the same server its {@code <name>.base-url} property
 * points at; resolving from the discovered map alone could disagree with an override applied later
 * in the same context. {@code httpMockServersReleaseListener} releases that exact same resolved
 * map when the context closes, bounding a server's lifetime to its last referencing context rather
 * than the whole JVM.
 */
@AutoConfiguration
@ConditionalOnClass(value = WireMockServer.class, name = "org.flowable.engine.RuntimeService")
public class FlowableTestHttpStubAutoConfiguration {

  /**
   * Resolves and retains this context's final set of HTTP mock servers, keyed by service name.
   */
  @Bean
  @ConditionalOnMissingBean
  HttpMockServers httpMockServers(Environment environment) {
    final Map<String, String> resolved = HttpMockServiceRegistry.resolve(environment);
    final Map<String, WireMockServer> servers = new LinkedHashMap<>();
    resolved.forEach(
        (name, location) ->
            servers.put(name, EmbeddedFlowableHttpMockSupport.retain(name, location)));
    return new HttpMockServers(servers);
  }

  /**
   * Releases this context's resolved server map on {@link ContextClosedEvent}, decrementing each
   * server's refcount so it stops once no context still references it.
   */
  @Bean
  ApplicationListener<ContextClosedEvent> httpMockServersReleaseListener(Environment environment) {
    final Map<String, String> resolved = HttpMockServiceRegistry.resolve(environment);
    return event -> resolved.forEach(EmbeddedFlowableHttpMockSupport::release);
  }

  /**
   * Invokes each {@link HttpStubConfigurer} bean once per discovered service, after the
   * declarative JSON mappings are already loaded. Configurers only run the first time a given
   * {@code WireMockServer} instance is seen ({@link
   * EmbeddedFlowableHttpMockSupport#markConfiguredOnce}), not once per context that retains it, so
   * a shared, JVM-lifetime server does not accumulate a duplicate stub registration from every
   * context reusing it.
   */
  @Bean
  InitializingBean httpStubConfigurerInvoker(
      HttpMockServers httpMockServers, ObjectProvider<List<HttpStubConfigurer>> configurers) {
    return () -> {
      final List<HttpStubConfigurer> beans = configurers.getIfAvailable(List::of);
      if (beans.isEmpty()) {
        return;
      }
      httpMockServers
          .asMap()
          .forEach(
              (name, server) -> {
                if (EmbeddedFlowableHttpMockSupport.markConfiguredOnce(server)) {
                  for (final HttpStubConfigurer configurer : beans) {
                    configurer.configure(name, server);
                  }
                }
              });
    };
  }
}
