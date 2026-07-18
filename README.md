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
- [Configuration reference](#configuration-reference)
- [Flowable version compatibility](#flowable-version-compatibility)
- [Troubleshooting](#troubleshooting)
- [Status](#status)

## Modules

| Module | Contents |
|---|---|
| `flowable-test-core` | Annotations, `ProcessTestHarness`, `KafkaTestBridge`, HTTP stub types. No auto-configuration. |
| `flowable-test-autoconfigure` | `@AutoConfiguration` classes, `EnvironmentPostProcessor`s, `ContextCustomizerFactory`. Has its own internal test suite (test-scope only) validating every capability against a real, pinned Flowable engine. |
| `flowable-test-spring-boot-starter` | The artifact you actually depend on — a thin aggregator of the two above. |
| `flowable-test-example` | A real Spring Boot + Flowable + Kafka Event Registry order-processing app depending on `flowable-test-spring-boot-starter` (test scope) like any external consumer. Not part of the release artifacts — validates the starter end to end. |

## Getting started

### 1. Add the dependency

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

### 2. Add optional capabilities (only if you need them)

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

### 3. Write your first test

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

### 4. Run it

```
mvn test
```

No Docker, no Testcontainers, no manually-maintained `@EmbeddedKafka(topics = ...)` list, no
hand-rolled WireMock server bootstrap. See [`flowable-test-example`](flowable-test-example) for a
complete, runnable reference consumer exercising every capability below against a real domain.

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

#### Pointing at a real broker instead

Set `flowable.test.kafka.enabled=false` and point `spring.kafka.bootstrap-servers` at a real broker
(Docker, CI, Testcontainers) — the starter skips provisioning its own embedded broker and Flowable's
Kafka Event Registry reads that property exactly as it would in production. `@Autowired
KafkaTestBridge` still just works, no manual `@Bean` needed, as long as your project declares real
Kafka Event Registry `*.channel` descriptors (the same signal that decides whether an embedded
broker gets started at all):

```yaml
flowable:
  test:
    kafka:
      enabled: false
spring:
  kafka:
    bootstrap-servers: localhost:9093
```

Topics still need to pre-exist on that broker, or `auto.create.topics.enable=true` set there — the
starter's own topic auto-provisioning only applies to the embedded broker it starts itself.

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

Waiting on an async step (a service task backed by a Kafka Event Registry send-event, a call
activity, or any node that resolves off the calling thread) wakes on the engine's own events
instead of sleeping a fixed interval, and fails fast — well before the timeout — if the async work
throws and gets parked as a dead-letter job rather than silently burning the full wait:

```java
harness.awaitActivity(instance.getId(), "receiveShippingConfirmation", Duration.ofSeconds(5));
harness.triggerMessage(instance.getId(), "shippingConfirmed");
harness.awaitEnded(instance.getId(), Duration.ofSeconds(5));
```

`triggerSignal`/`triggerMessage` resume an execution waiting on the corresponding BPMN catch event;
`forceTimerDue` skips a boundary/intermediate timer's real-world duration rather than the test
waiting it out:

```java
harness.forceTimerDue(instance.getId(), "escalationTimer");
harness.triggerSignal("orderCancelled"); // process-instance-agnostic: resumes every waiting instance
```

Assertions chain like any AssertJ assertion and resolve variables from live runtime state while the
process instance is active, or from history once it has ended:

```java
harness.assertThat(instance.getId())
    .hasEndedAt("endEventCompleted")
    .hasVariables(Map.of("approved", true, "orderId", "abc-123"));
```

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

## Configuration reference

Every property below is namespaced under `flowable.test.*`. IDE autocomplete and inline
descriptions work for all of them out of the box — `flowable-test-autoconfigure` ships typed
metadata (`META-INF/spring-configuration-metadata.json`, generated from a `@ConfigurationProperties`
record for the diagnostics group and hand-authored for the rest) that IntelliJ and VS Code's Spring
tooling both pick up automatically, no consumer-side setup required.

| Property | Default | Description |
|---|---|---|
| `flowable.test.datasource.provider` | `auto` | `auto` \| `h2` \| `embedded-postgres` — which `DataSource` backs the embedded engine. |
| `flowable.test.datasource.embedded-postgres.instance-scope` | `per-context` | `per-context` \| `shared` — a native embedded-postgres process per Spring context, or one JVM-wide process with a logical database per context. |
| `flowable.test.kafka.enabled` | `true` | Whether the embedded Kafka broker capability is active at all. |
| `flowable.test.kafka.channel-location` | `classpath*:**/*.channel` | Classpath pattern scanned for Flowable Kafka Event Registry descriptors when auto-discovering topics. |
| `flowable.test.kafka.partitions` | `1` | Partition count for every auto-discovered and additional Kafka topic. |
| `flowable.test.kafka.broker-scope` | `shared` | `shared` \| `per-context` — one JVM-wide embedded broker singleton, or a fresh broker per Spring context. |
| `flowable.test.http-mocks.enabled` | `true` | Whether declarative WireMock HTTP stubbing is active at all. |
| `flowable.test.http-mocks.root` | `classpath:httpmocks` | Classpath root scanned for one WireMock mapping folder per immediate subfolder. |
| `flowable.test.http-mocks.services` | *(discover every subfolder)* | Explicit, declared list of service names to start; a declared name with no matching `mappings` folder fails fast. |
| `flowable.test.processes.root` | `classpath:processes` | Classpath root BPMN process file names resolve against. |
| `flowable.test.processes.deploy` | *(Flowable's own classpath scan)* | Explicit, declared list of BPMN process file names to deploy by default. |
| `flowable.test.diagnostics.enabled` | `true` | Whether BPMN failure diagnostics are active at all. |
| `flowable.test.diagnostics.max-tracked-process-instances` | `50` | Cap on how many process instances a single failure runs full diagnostics queries against. |
| `flowable.test.diagnostics.max-activity-trail-entries` | `20` | How many activity-trail entries a diagnostics snapshot includes. |
| `flowable.test.diagnostics.max-variable-value-length` | `500` | How many characters of a rendered variable value a snapshot includes. |
| `flowable.test.diagnostics.include-failed-jobs` | `true` | Whether a snapshot includes dead-letter job failures. |
| `flowable.test.diagnostics.redacted-variable-names` | `password,token,secret,apikey,authorization,ssn` | Variable names (case-insensitive substring match) rendered as `[REDACTED]`. |

A misspelled key under `flowable.test.diagnostics.*` specifically fails context refresh rather than
silently keeping its default — see [Troubleshooting](#troubleshooting). The other properties above
are read before the `ApplicationContext` exists (`EnvironmentPostProcessor`s and `Condition`s that
decide which `DataSource`/broker/mock servers to start), so they can't be validated the same way;
a typo there falls back to the property's default instead.

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

## Troubleshooting

**A test unexpectedly uses embedded-postgres instead of H2 (or vice versa).**
`flowable.test.datasource.provider` defaults to `auto`, which prefers embedded-postgres the moment
`io.zonky.test:embedded-postgres` lands on the test classpath for *any* reason — even a single,
unrelated test class needing it. The first time this happens, a one-time warning is logged naming
the property. Set `flowable.test.datasource.provider` explicitly (`h2` or `embedded-postgres`) to
stop the choice depending on which other test dependency happens to be present.

**Context refresh fails with a Flowable version message before any test runs.**
`FlowableCompatibilityGuardAutoConfiguration` checks the consumer's actual runtime
`ProcessEngine.VERSION` against the supported range (see
[Flowable version compatibility](#flowable-version-compatibility)) and fails fast with an
actionable message rather than surfacing as an obscure `NoSuchMethodError` mid-test. Check which
`org.flowable:flowable-spring-boot-starter` version your project actually resolves
(`mvn dependency:tree`), not just what your own `pom.xml` declares.

**A declared HTTP mock service or BPMN process fails before the context starts refreshing.**
This is by design: `flowable.test.http-mocks.services` and `flowable.test.processes.deploy` are
opt-in allow-lists, and a name declared there with no matching `mappings` folder or `.bpmn20.xml`
file fails fast rather than silently deploying fewer services/processes than the test expects.
Double-check the name — BPMN process **file** names and the `<process id="...">` declared inside
them commonly differ by hyphenation (e.g. `order-processing.bpmn20.xml` vs `orderProcessing`).

**Changing `@EmbeddedFlowableKafka(partitions = ...)` doesn't change a topic's partition count.**
The embedded broker is a JVM-wide singleton by default (`flowable.test.kafka.broker-scope=shared`),
started once per JVM. `partitions` only applies to that annotation's own `additionalTopics()` and
cannot retroactively repartition a topic the Event Registry channel scan, or an earlier test class
in the same JVM, already created. Use `broker-scope: per-context` if a test class genuinely needs a
different partition count, at the cost of a fresh broker start per context.

**A misspelled `flowable.test.diagnostics.*` property now fails context refresh.**
This is intentional: that group binds through a typed `@ConfigurationProperties` record with
`ignoreUnknownFields = false`, specifically so a typo fails loudly instead of silently keeping its
default. Check the property name against the [Configuration reference](#configuration-reference)
table.

**Diagnostics tracking under JUnit 5 parallel test execution.**
See the caveat at the end of [BPMN failure diagnostics](#bpmn-failure-diagnostics) — not currently
verified under parallel execution within a shared Spring context. Sequential execution (JUnit 5's
default) is unaffected.

## Status

Every capability has a passing validation test, both internal (`flowable-test-autoconfigure`'s own
suite, run against a real pinned Flowable 7.1.0 engine) and end-to-end (`flowable-test-example`, a
real Spring Boot + Flowable + Kafka Event Registry order-processing app that depends on this
starter exactly like an external consumer would — see its own README for what it proves). Both
suites run on every push via CI's `mvn clean test`, across the full supported Flowable range
(7.0.0 and 7.1.0).
