# flowable-test-spring-boot-starter

A Docker-free Spring Boot test starter for **any** Flowable BPMN project: embedded DB, embedded
Kafka (topics auto-discovered from Flowable's own Kafka Event Registry `*.channel` files),
declarative HTTP mocking (plain WireMock mapping JSON, convention over configuration), and a
generic process-testing DSL. Built as a Testcontainers-free replacement for full end-to-end BPMN
process tests in CI environments where Docker isn't available.

This is a library, not an application tied to any one BPMN process — every public API operates on
process instance IDs, activity IDs, candidate group names, and plain classpath conventions. See
[`flowable-bpmn-masterclass`](../flowable-bpmn-masterclass) for a reference consumer.

## Modules

| Module | Contents |
|---|---|
| `flowable-test-core` | Annotations, `ProcessTestHarness`, `KafkaTestBridge`, HTTP stub types. No auto-configuration. |
| `flowable-test-autoconfigure` | `@AutoConfiguration` classes, `EnvironmentPostProcessor`s, `ContextCustomizerFactory`. Has its own internal test suite (test-scope only) validating every capability against a real, pinned Flowable engine. |
| `flowable-test-spring-boot-starter` | The artifact you actually depend on — a thin aggregator of the two above. |

## Getting started

```xml
<dependency>
    <groupId>com.flowabletest</groupId>
    <artifactId>flowable-test-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Kafka and HTTP-mocking support are opt-in: add these yourself if you need them (never forced on a
project that doesn't use them):

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <scope>test</scope>
</dependency>
```

Replace your `@SpringBootTest @ActiveProfiles("test")` stack with one annotation:

```java
@FlowableProcessTest
class OrderProcessTest {

    @Autowired ProcessTestHarness harness;
    @Autowired RuntimeService runtimeService;

    @Test
    void managerApprovalCompletesTheOrder() {
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("orderProcessing", vars);

        harness.completeSingleTask(pi.getId(), "managers", Map.of("approved", true));

        harness.assertThat(pi.getId()).hasEndedAt("endEventCompleted");
    }
}
```

## Capabilities

### Embedded database

H2 by default — no configuration needed, this is just Spring Boot's own embedded-database
support. Opt into a Docker-free embedded Postgres (native binary, no container) for
Postgres-specific SQL:

```yaml
flowable:
  test:
    datasource:
      provider: embedded-postgres
```

requires `io.zonky.test:embedded-postgres` on your test classpath.

### Embedded Kafka

If `spring-kafka-test` is on your classpath, topics are discovered automatically by scanning your
project's Flowable Kafka Event Registry `*.channel` descriptors (both the outbound `"topic"` and
inbound `"topics"` shapes) — no `@EmbeddedKafka(topics = {...})` list to hand-maintain.
`@Autowired KafkaTestBridge` gives you `send(topic, key, value)` /
`awaitMessage(topic, predicate, timeout)` without writing raw producer/consumer boilerplate.

### Declarative HTTP mocking

If `wiremock-standalone` is on your classpath, drop plain WireMock mapping JSON under
`src/test/resources/httpmocks/<service-name>/mappings/*.json` and the corresponding
`<service-name>.base-url` property is injected automatically — no annotation, no Java stub code:

```json
{
  "request": { "method": "POST", "urlPath": "/v1/charge" },
  "response": { "status": 200, "jsonBody": { "status": "SUCCESS" } }
}
```

`@MockExternalService(name = "payment-gateway", stubs = "classpath:httpmocks/payment-gateway-timeout")`
on a specific test class redirects just that class to an alternate stub folder (e.g. to simulate a
failure path), without touching the shared default.

### Process assertions / harness

`ProcessTestHarness` (autowired once a `ProcessEngine` exists) wraps the
`RuntimeService`/`TaskService`/`HistoryService` boilerplate that otherwise gets duplicated in
every Flowable test class: `completeSingleTask`, `awaitTaskForCandidateGroup`, `awaitEnded`,
`awaitCallActivityChild`, and a `ProcessInstanceAssert` (`hasEndedAt`, `isActive`,
`hasNoTaskForCandidateGroup`).

## Flowable version compatibility

| Starter version | Supported Flowable range |
|---|---|
| 0.1.x | `[7.0.0, 8.0.0)` |

This starter compiles against Flowable as a `provided`-scope dependency and never bundles it — the
consumer's own `org.flowable:flowable-spring-boot-starter` is always the sole source of the engine
at runtime, so there's no version conflict to worry about (Maven `provided` scope doesn't
propagate transitively). At test startup, `FlowableCompatibilityGuardAutoConfiguration` checks the
consumer's actual runtime `ProcessEngine.VERSION` against the supported range above and fails fast
with an actionable message if it's outside it, rather than surfacing as an obscure
`NoSuchMethodError` mid-test.

## Design rationale

See the design doc in the `flowable-bpmn-masterclass` reference project
(`claudedocs/flowable-test-starter-design.md`) for the full rationale behind every decision above
— module boundary, why `provided` scope specifically prevents version conflicts, why HTTP mocking
reuses WireMock's native format instead of a bespoke schema, and why the Kafka/HTTP-mock timing
requires `EnvironmentPostProcessor`/`ContextCustomizerFactory` rather than plain `@Bean` methods.

## Status

Freshly scaffolded, untested against a real consumer project. Every capability has passing
internal validation tests (`flowable-test-autoconfigure`'s own test suite, run against a real
pinned Flowable 7.1.0 engine) but the starter has not yet been exercised against
`flowable-bpmn-masterclass`'s actual `OrderProcessScenariosTest` migration.
