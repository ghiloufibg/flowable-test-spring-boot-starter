package com.flowabletest.autoconfigure.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.http.HttpMockServers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Proves the whole declarative HTTP-mocking capability end to end: a plain WireMock mapping JSON
 * file under {@code src/test/resources/httpmocks/demo-service/mappings/} is auto-discovered, a
 * WireMock server is started for it before context refresh, and {@code demo-service.base-url} is
 * injected -- with no annotation and no Java stub-building code anywhere in this test.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class FlowableTestHttpStubAutoConfigurationTest {

  @Autowired Environment environment;
  @Autowired HttpMockServers httpMockServers;

  @Test
  void baseUrlPropertyIsInjectedForTheDiscoveredService() {
    assertThat(environment.getProperty("demo-service.base-url")).isNotBlank();
    assertThat(httpMockServers.get("demo-service")).isNotNull();
  }

  @Test
  void theDeclarativeStubActuallyServesTheConfiguredResponse() throws Exception {
    final String baseUrl = environment.getProperty("demo-service.base-url");

    final HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("hi from demo-service");
  }
}
