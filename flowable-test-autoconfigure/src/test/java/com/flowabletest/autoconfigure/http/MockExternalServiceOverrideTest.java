package com.flowabletest.autoconfigure.http;

import com.flowabletest.autoconfigure.testapp.SampleFlowableApplication;
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.annotation.MockExternalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link MockExternalService} redirects a specific test class to an alternate stub
 * folder (design doc section 4.3's escape hatch), instead of the shared
 * {@code httpmocks/demo-service} default that {@link FlowableTestHttpStubAutoConfigurationIT}
 * uses -- both are "demo-service" but must resolve to different content.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
@MockExternalService(name = "demo-service", stubs = "classpath:httpmocks-alt/demo-service")
class MockExternalServiceOverrideTest {

    @Autowired
    Environment environment;

    @Test
    void usesTheAlternateStubFolderInsteadOfTheConventionDefault() throws Exception {
        String baseUrl = environment.getProperty("demo-service.base-url");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).contains("hi from ALT demo-service");
    }
}
