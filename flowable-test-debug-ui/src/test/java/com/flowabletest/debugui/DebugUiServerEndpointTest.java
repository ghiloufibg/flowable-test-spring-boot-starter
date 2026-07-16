package com.flowabletest.debugui;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.debugui.testapp.SampleFlowableApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the three routes actually work end to end over real HTTP -- not just that the handler
 * classes compile -- against the {@link DebugUiServer} bean this module's own {@code
 * application.yml} (enabled by default) already started.
 */
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class DebugUiServerEndpointTest {

  @Autowired DebugUiServer debugUiServer;
  @Autowired RuntimeService runtimeService;
  @Autowired RepositoryService repositoryService;

  private final HttpClient httpClient = HttpClient.newHttpClient();

  @BeforeEach
  void deployDiagramFixtureProcess() {
    if (repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey("diagramFixtureProcess")
            .count()
        == 0) {
      repositoryService
          .createDeployment()
          .addClasspathResource("processes/diagram-fixture.bpmn20.xml")
          .deploy();
    }
  }

  @Test
  void listPageLinksToATrackedProcessInstance() throws Exception {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagramFixtureProcess");

    final HttpResponse<String> response = get("/");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(contentType -> assertThat(contentType).contains("text/html"));
    assertThat(response.body()).contains(instance.getId());
  }

  @Test
  void detailPageShowsVariablesAndTheDiagramImage() throws Exception {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "diagramFixtureProcess", Map.of("orderId", "abc-123"));

    final HttpResponse<String> response = get("/instances/" + instance.getId());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("orderId").contains("abc-123").contains("diagram.png");
  }

  @Test
  void detailPageReturns404ForAnUnknownInstance() throws Exception {
    final HttpResponse<String> response = get("/instances/does-not-exist");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void diagramEndpointReturnsAPngImage() throws Exception {
    final ProcessInstance instance =
        runtimeService.startProcessInstanceByKey("diagramFixtureProcess");

    final HttpResponse<byte[]> response =
        httpClient.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + debugUiServer.port()
                            + "/instances/"
                            + instance.getId()
                            + "/diagram.png"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png");
  }

  @Test
  void handlesEachRequestOnItsOwnVirtualThread() throws Exception {
    final AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);
    final CountDownLatch taskRan = new CountDownLatch(1);

    debugUiServer
        .executor()
        .execute(
            () -> {
              ranOnVirtualThread.set(Thread.currentThread().isVirtual());
              taskRan.countDown();
            });

    assertThat(taskRan.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(ranOnVirtualThread.get()).isTrue();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return httpClient.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + debugUiServer.port() + path))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
