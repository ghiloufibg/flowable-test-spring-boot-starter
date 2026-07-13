package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.MockExternalService;
import com.flowabletest.core.http.HttpMockServers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Proves {@link MockExternalService} redirects a specific test class to an alternate stub folder
 * (design doc section 4.3's escape hatch), instead of the shared {@code httpmocks/demo-service}
 * default that {@link FlowableTestHttpStubAutoConfigurationTest} uses -- both are "demo-service"
 * but must resolve to different content.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
@MockExternalService(name = "demo-service", stubs = "classpath:httpmocks-alt/demo-service")
class MockExternalServiceOverrideTest {

  @Autowired Environment environment;
  @Autowired HttpMockServers httpMockServers;

  @Test
  void usesTheAlternateStubFolderInsteadOfTheConventionDefault() throws Exception {
    final String baseUrl = environment.getProperty("demo-service.base-url");

    final HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.body()).contains("hi from ALT demo-service");
  }

  @Test
  void httpMockServersResolvesTheSameOverridingServerAsTheBaseUrlProperty() {
    final String baseUrl = environment.getProperty("demo-service.base-url");

    final int resolvedPort = httpMockServers.get("demo-service").port();

    assertThat("http://localhost:" + resolvedPort).isEqualTo(baseUrl);
  }
}
