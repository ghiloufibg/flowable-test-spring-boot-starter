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
 * Exposes the servers started by {@link FlowableTestHttpStubEnvironmentPostProcessor} (or, for a
 * test overriding a service's folder, {@link MockExternalServiceContextCustomizer}) as a regular
 * bean, and invokes any {@link HttpStubConfigurer} beans once per discovered service after the
 * declarative JSON mappings are already loaded (design doc section 4.3).
 *
 * <p>{@code httpMockServers} resolves this context's final, override-applied service map via {@link
 * HttpMockServiceRegistry} and {@code retain}s each entry, so a context with a
 * {@code @MockExternalService} override always sees the same (overriding) server its {@code
 * <name>.base-url} property points at -- resolving from {@code discovered} alone, as before, could
 * disagree with an override applied later in the same context (design doc:
 * wiremock-shared-server-fixes-design.md, fix #1). {@code httpMockServersReleaseListener} releases
 * that exact same resolved map when this context closes, so a server's lifetime is bounded by its
 * last referencing context rather than the whole JVM (fix #2).
 */
@AutoConfiguration
@ConditionalOnClass(value = WireMockServer.class, name = "org.flowable.engine.RuntimeService")
public class FlowableTestHttpStubAutoConfiguration {

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

  @Bean
  ApplicationListener<ContextClosedEvent> httpMockServersReleaseListener(Environment environment) {
    final Map<String, String> resolved = HttpMockServiceRegistry.resolve(environment);
    return event -> resolved.forEach(EmbeddedFlowableHttpMockSupport::release);
  }

  @Bean
  InitializingBean httpStubConfigurerInvoker(
      HttpMockServers httpMockServers, ObjectProvider<List<HttpStubConfigurer>> configurers) {
    return () -> {
      final List<HttpStubConfigurer> beans = configurers.getIfAvailable(List::of);
      for (final HttpStubConfigurer configurer : beans) {
        httpMockServers.asMap().forEach(configurer::configure);
      }
    };
  }
}
