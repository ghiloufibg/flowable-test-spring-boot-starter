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
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Exposes the servers started by {@link FlowableTestHttpStubEnvironmentPostProcessor} (or, for a
 * test overriding a service's folder, {@link MockExternalServiceContextCustomizer}) as a regular
 * bean, and invokes any {@link HttpStubConfigurer} beans once per discovered service after the
 * declarative JSON mappings are already loaded (design doc section 4.3).
 */
@AutoConfiguration
@ConditionalOnClass(value = WireMockServer.class, name = "org.flowable.engine.RuntimeService")
public class FlowableTestHttpStubAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  HttpMockServers httpMockServers(Environment environment) {
    String discovered = environment.getProperty("flowable.test.http-mocks.discovered", "");
    Map<String, WireMockServer> servers = new LinkedHashMap<>();
    if (!discovered.isBlank()) {
      for (String pair : discovered.split(",")) {
        String[] parts = pair.split("=", 2);
        if (parts.length != 2) {
          continue;
        }
        WireMockServer server = EmbeddedFlowableHttpMockSupport.get(parts[0], parts[1]);
        if (server != null) {
          servers.put(parts[0], server);
        }
      }
    }
    return new HttpMockServers(servers);
  }

  @Bean
  InitializingBean httpStubConfigurerInvoker(
      HttpMockServers httpMockServers, ObjectProvider<List<HttpStubConfigurer>> configurers) {
    return () -> {
      List<HttpStubConfigurer> beans = configurers.getIfAvailable(List::of);
      for (HttpStubConfigurer configurer : beans) {
        httpMockServers.asMap().forEach(configurer::configure);
      }
    };
  }
}
