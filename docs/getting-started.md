# Getting started

A quick setup guide for adding `flowable-test-spring-boot-starter` to a Flowable BPMN project. For
the full capability reference — embedded Kafka, declarative HTTP mocking, the complete
`ProcessTestHarness` API, BPMN failure diagnostics, Flowable version compatibility — see the
[root README](../README.md).

## 1. Add the dependency

```xml
<dependency>
    <groupId>com.flowabletest</groupId>
    <artifactId>flowable-test-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Your project must already depend on `org.flowable:flowable-spring-boot-starter`. This starter never
bundles the Flowable engine itself — it only wires test infrastructure around whatever engine your
project already provides.

## 2. Add optional capabilities (only if you need them)

Kafka and HTTP mocking are opt-in — neither is ever forced on a project that doesn't use it:

```xml
<!-- only if your process uses Flowable's Kafka Event Registry -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- only if a delegate makes an outbound HTTP call you want to stub -->
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <scope>test</scope>
</dependency>
```

## 3. Write your first test

Replace a hand-assembled `@SpringBootTest` + `@ActiveProfiles("test")` stack with one annotation:

```java
import com.flowabletest.core.annotation.FlowableProcessTest;
import com.flowabletest.core.harness.ProcessTestHarness;
import java.util.Map;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@FlowableProcessTest
class OrderProcessTest {

  @Autowired RuntimeService runtimeService;
  @Autowired ProcessTestHarness harness;

  @Test
  void managerApprovalCompletesTheOrder() {
    ProcessInstance instance =
        runtimeService.startProcessInstanceByKey(
            "orderProcessing", Map.of("orderId", "abc-123"));

    harness.completeSingleTask(instance.getId(), "managers", Map.of("approved", true));

    harness.assertThat(instance.getId()).hasEndedAt("endEventCompleted");
  }
}
```

No `application.yml` needed for the basics — an embedded H2 database (or embedded-postgres, if that
dependency is on your test classpath) is wired automatically.

## 4. Run it

```
mvn test
```

No Docker, no Testcontainers, no manually-maintained `@EmbeddedKafka(topics = ...)` list, no
hand-rolled WireMock server bootstrap.

## Next steps

- Full capability reference: see the [root README](../README.md).
- A complete, runnable reference consumer exercising every capability against a real domain: see
  [`flowable-test-example`](../flowable-test-example).
