# flowable-test-spring-boot-starter

[![CI](https://github.com/ghiloufibg/flowable-test-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/ghiloufibg/flowable-test-spring-boot-starter/actions/workflows/ci.yml)

A Docker-free Spring Boot test starter for **any** Flowable BPMN project: embedded database,
embedded Kafka (topics auto-discovered from Flowable's own Kafka Event Registry `*.channel` files),
declarative HTTP mocking (plain WireMock mapping JSON, convention over configuration), and a
generic process-testing DSL. Built as a Testcontainers-free replacement for full end-to-end BPMN
process tests in CI environments where Docker isn't available.

This is a library, not an application tied to any one BPMN process — every public API operates on
process instance IDs, activity IDs, candidate group names, and plain classpath conventions. See
[`flowable-test-example`](flowable-test-example) for a reference consumer, owned by this repo, that
depends on this starter exactly like an external project would.

## Contents

- [Modules](#modules)
- [Getting started](#getting-started)
- [Capabilities](#capabilities)
  - [Embedded database](#embedded-database)
  - [Embedded Kafka](#embedded-kafka)
  - [Declarative HTTP mocking](#declarative-http-mocking)
  - [Process assertions / harness](#process-assertions--harness)
  - [BPMN failure diagnostics](#bpmn-failure-diagnostics)
- [Flowable version compatibility](#flowable-version-compatibility)
- [Status](#status)

## Modules

| Module | Contents |
|---|---|
| `flowable-test-core` | Annotations, `ProcessTestHarness`, `KafkaTestBridge`, HTTP stub types. No auto-configuration. |
| `flowable-test-autoconfigure` | `@AutoConfiguration` classes, `EnvironmentPostProcessor`s, `ContextCustomizerFactory`. Has its own internal test suite (test-scope only) validating every capability against a real, pinned Flowable engine. |
| `flowable-test-spring-boot-starter` | The artifact you actually depend on — a thin aggregator of the two above. |
| `flowable-test-example` | A real Spring Boot + Flowable + Kafka Event Registry order-processing app depending on `flowable-test-spring-boot-starter` (test scope) like any external consumer. Not part of the release artifacts — validates the starter end to end. |

## Getting started

See [`docs/getting-started.md`](docs/getting-started.md) for a minimal setup walkthrough. The short
version:

```xml
<dependency>
    <groupId>com.flowabletest</groupId>
    <artifactId>flowable-test-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Kafka and HTTP-mocking support are opt-in — add these yourself only if you need them:

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

Replace a hand-assembled `@SpringBootTest` + `@ActiveProfiles("test")` stack with one annotation:

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

H2 by default, via Spring Boot's own embedded-database support — no configuration needed. If
`io.zonky.test:embedded-postgres` is on your test classpath, it's auto-detected and replaces H2
automatically: a Docker-free, native-binary alternative for delegates that rely on Postgres-specific
SQL (JSON columns, arrays) that H2's dialect emulation can't cover.

Override the auto-detection with `flowable.test.datasource.provider`:

```yaml
flowable:
  test:
    datasource:
      provider: h2 # force H2 even if embedded-postgres is on the classpath
      # provider: embedded-postgres  # require embedded-postgres explicitly
      # provider: auto               # default: prefer embedded-postgres when present
```

By default, embedded-postgres forks a fresh native process per Spring context (`per-context`). Opt
into a single JVM-wide server shared across contexts — each still gets its own isolated logical
database — with `instance-scope: shared`:

```yaml
flowable:
  test:
    datasource:
      provider: embedded-postgres
      embedded-postgres:
        instance-scope: shared # per-context (default) | shared
```

### Embedded Kafka

If `spring-kafka-test` is on your classpath, topics are discovered automatically from your
project's Flowable Kafka Event Registry `*.channel` descriptors (both the outbound `"topic"` and
inbound `"topics"` shapes) — no `@EmbeddedKafka(topics = {...})` list to hand-maintain. `@Autowired
KafkaTestBridge` gives you `send(topic, key, value)` / `awaitMessage(topic, predicate, timeout)`
without producer/consumer boilerplate.

By default, the embedded broker is a JVM-wide singleton shared across every Spring context
(`shared`), following the same plain start-once pattern Spring Kafka's own docs recommend for
reusing a broker across test classes. A `TestExecutionListener` starts/stops each context's
Flowable-managed inbound Kafka Event Registry consumer container(s) at test class boundaries, so at
most one context's consumers are ever polling at once — even when Spring reuses a JVM across test
classes with different `@MockExternalService` configurations. Opt into a fresh broker per Spring
context instead (no lifecycle choreography needed, at the cost of a broker start per context):

```yaml
flowable:
  test:
    kafka:
      broker-scope: shared # shared (default) | per-context
```

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
on a specific test class redirects just that class to an alternate stub folder — e.g. to simulate a
failure path — without touching the shared default. The autowired `HttpMockServers` bean always
reflects whichever server a class's own override (if any) actually points at, and each WireMock
server's lifetime is tied to the Spring test contexts referencing it, not the whole JVM.

By default, every immediate subfolder under the configured root is discovered and started. Declare
`flowable.test.http-mocks.services` to make that explicit instead: only the listed names are
started (a declared name with no matching `mappings` folder fails fast, before the context even
starts refreshing), and any other folder — e.g. one that only ever exists as a
`@MockExternalService(stubs = ...)` override target — is left alone:

```yaml
flowable:
  test:
    http-mocks:
      services: # optional; absent = discover every subfolder (unchanged default)
        - fraud-check-service
```

### Process assertions / harness

`ProcessTestHarness` (autowired once a `ProcessEngine` exists) wraps the
`RuntimeService`/`TaskService`/`HistoryService`/`ManagementService` boilerplate that otherwise gets
duplicated in every Flowable test class:

| Category | Methods |
|---|---|
| Task completion | `completeSingleTask`, `completeOneTaskForCandidateGroup` (parallel multi-instance) |
| Event triggering | `triggerSignal`, `triggerMessage`, `forceTimerDue` |
| Node discovery | `currentActivityIds`, `activeExecutionCount` |
| Event-driven waits (wake on engine events, fail fast on a dead-letter job) | `awaitEnded`, `awaitTaskForCandidateGroup`, `awaitCallActivityChild`, `awaitActivity`, `awaitActivityCount` |
| Variables | `setVariables` |
| Assertions (`assertThat(processInstanceId)`) | `hasEndedAt`, `isActive`, `isWaitingAt`, `hasNoTaskForCandidateGroup`, `hasVariable`, `hasVariables` |

### BPMN failure diagnostics

Every `@FlowableProcessTest` failure — raised by the harness/assertions or by arbitrary test/delegate
code — is automatically enriched with a snapshot: current activity, process variables, activity
trail, pending tasks, and dead-letter job failures (the exception behind an async service task that
silently got parked as a retryable job, rather than failing the test directly). It's attached as a
suppressed exception on the original failure, so it shows up wherever the stack trace already does —
IDE, console, Surefire reports — with no reporting plugin to configure.

Object-type variables (a POJO, record, `List`, or `Map`) render as JSON rather than
`Object#toString()`, falling back to `toString()` if Jackson itself can't serialize the value, so a
diagnostics-rendering failure can never replace the real test failure.

On by default; disable it, or tune its limits, via:

```yaml
flowable:
  test:
    diagnostics:
      enabled: true                      # default true
      max-activity-trail-entries: 20     # default 20
      max-variable-value-length: 500     # default 500
      include-failed-jobs: true          # default true
      max-tracked-process-instances: 50  # default 50
      redacted-variable-names:           # default: password,token,secret,apikey,authorization,ssn
        - password
        - token
```

Process variable values are otherwise dumped verbatim into text that routinely ends up archived in
CI (Surefire reports, log aggregation); any variable whose name contains one of
`redacted-variable-names` (case-insensitive substring match) is rendered as `[REDACTED]` instead.
`max-tracked-process-instances` caps how many process instances a single test failure will run full
diagnostics queries against — instances beyond the cap are omitted, with a count of how many,
rather than silently truncated without saying so. A diagnostics-collection failure itself (a flaky
DB connection at the exact moment something has already gone wrong) is logged and swallowed, never
allowed to replace the real test failure it was trying to enrich.

Not currently verified under JUnit 5 parallel test execution: the process-instance tracking this
feature relies on is a single Spring-scoped bean reset per test method, and a shared Spring context
running test methods concurrently could see one method's reset race another's in-flight failure
collection. Sequential execution (JUnit 5's default) is unaffected.

## Flowable version compatibility

| Starter version | Supported Flowable range |
|---|---|
| 0.1.x | `[7.0.0, 8.0.0)` |

This starter compiles against Flowable as a `provided`-scope dependency and never bundles it — the
consumer's own `org.flowable:flowable-spring-boot-starter` is always the sole source of the engine
at runtime, so there's no version conflict to worry about. At test startup,
`FlowableCompatibilityGuardAutoConfiguration` checks the consumer's actual runtime
`ProcessEngine.VERSION` against the supported range above and fails fast with an actionable message
if it's outside it, rather than surfacing as an obscure `NoSuchMethodError` mid-test.

CI (`.github/workflows/ci.yml`) runs the full test suite once per Flowable release at the ends of
the supported range (currently `7.0.0` and `7.1.0`), so the range above is empirically verified on
every push rather than just asserted.

## Status

Every capability has a passing validation test, both internal (`flowable-test-autoconfigure`'s own
suite, run against a real pinned Flowable 7.1.0 engine) and end-to-end (`flowable-test-example`, a
real Spring Boot + Flowable + Kafka Event Registry order-processing app that depends on this
starter exactly like an external consumer would — see its own README for what it proves). Both
suites run on every push via CI's `mvn clean test`, across the full supported Flowable range
(7.0.0 and 7.1.0).
