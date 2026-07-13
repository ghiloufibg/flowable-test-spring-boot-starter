# `flowable-test-spring-boot-starter` — Design

Status: design-only, not yet implemented. This repo (`flowable-bpmn-masterclass`) is the reference consumer that will validate the starter once built.

## 1. Problem

CI cannot run Docker, so `OrderProcessScenariosTest`'s `@Testcontainers` (PostgreSQL + Kafka containers) can't run in the pipeline. We need a Docker-free way to get the same full end-to-end BPMN process test coverage: real-ish DB, real-ish Kafka, and a way to mock external HTTP dependencies — packaged as a **generic** Spring Boot test starter usable by any Flowable project, not something coupled to this project's order-processing domain.

## 2. Scope boundary

Nothing in the starter may reference `Order`, `OrderProcessService`, `order-processing.bpmn20.xml`, or any masterclass-specific type. Every public API operates only on generic Flowable primitives (`RuntimeService`/`TaskService`/`HistoryService`/`RepositoryService`, process instance IDs, activity IDs, candidate groups, process variables), generic Kafka topic/key/value strings, and generic HTTP stub definitions. If a capability can't be expressed without a masterclass-specific concept, it belongs in this repo's own test helpers, not the starter.

## 3. Module identity & location

**Decision: separate repo/artifact**, not a sibling module in this repo. Own Maven coordinates (e.g. `com.flowabletest:flowable-test-spring-boot-starter`), own git history, installed/published independently. This forces the domain-agnostic boundary structurally — nothing in it can accidentally import a masterclass type — rather than relying on code-review discipline in a shared repo.

### Module layout (Spring Boot's starter/autoconfigure split)

```
flowable-test-spring-boot-starter/
├── flowable-test-autoconfigure/
│   │  # @ConditionalOnClass-gated auto-configuration, one class per capability
│   ├── FlowableTestDatasourceAutoConfiguration      (always active)
│   ├── FlowableTestKafkaAutoConfiguration            (@ConditionalOnClass(EmbeddedKafkaBroker.class))
│   ├── FlowableTestHttpStubAutoConfiguration          (@ConditionalOnClass(WireMockServer.class))
│   └── FlowableTestAssertionsAutoConfiguration        (always active — registers ProcessTestHarness bean)
├── flowable-test-core/
│   │  # annotations + DSL, no Spring Boot auto-config coupling
│   ├── annotation/  @FlowableProcessTest, @MockExternalService, @EmbeddedFlowableKafka
│   ├── assertions/  ProcessInstanceAssert, HistoricActivityAssert (AssertJ-style)
│   ├── harness/     ProcessTestHarness (task completion, wait-state polling)
│   ├── kafka/       KafkaTestBridge (generic send/awaitMessage)
│   └── http/        HttpStubRegistrar, HttpStubConfigurer (SPI interface)
└── flowable-test-starter/          # empty POM, just aggregates the above + optional deps
```

Consumers add one dependency (`flowable-test-spring-boot-starter`, `test` scope) and, optionally, `wiremock` / `spring-kafka-test` on their own classpath to light up the matching auto-configuration — the starter never forces Kafka or WireMock on a project that doesn't use them.

## 4. Capabilities

### 4.1 Embedded DB — always on, pluggable fidelity

**Decision: H2 fallback, `embedded-postgres` auto-detected.**

- Fallback: H2 in-memory, matching this repo's existing `OrderProcessTest` setup. Fast startup, no external process. Wired by Spring Boot's own `DataSourceAutoConfiguration`, not this starter.
- Auto-detected: if `io.zonky.test:embedded-postgres` (downloads a native Postgres binary, runs it as a local OS process — **no Docker**) is on the consumer's test classpath, `FlowableTestDatasourceAutoConfiguration` picks it automatically and it replaces H2 — no property needed. For projects whose delegates rely on Postgres-specific SQL (JSON columns, arrays) where H2's dialect emulation isn't enough. This is the direct, Docker-free replacement for `OrderProcessScenariosTest`'s Testcontainers `PostgreSQLContainer`.
- `flowable.test.datasource.provider` overrides the auto-detection: `auto` (default, prefer embedded-postgres when present) `|h2` (force H2 even if embedded-postgres is present) `|embedded-postgres` (require it). `EmbeddedPostgresPreferredCondition` implements the three-way choice; both providers are wired as regular `DataSource` beans — no `@DynamicPropertySource` boilerplate required in the consumer's test class. `FlowableTestDatasourceAutoConfiguration` runs `@AutoConfigureBefore(DataSourceAutoConfiguration.class)` so its embedded-Postgres bean is already present by the time Spring Boot's own `@ConditionalOnMissingBean(DataSource.class)` for H2 is evaluated.

### 4.2 Embedded Kafka — auto-discovers topics from Flowable's own Event Registry files

`@EmbeddedKafka`'s biggest pain point today is hardcoding the topic list (see `OrderProcessTest` line 44: `topics = {"order-events", "order-audit-trail", "payment-callbacks"}`). Flowable's Kafka Event Registry file format (`*.channel`/`*.event` JSON, anywhere on the classpath) is itself a generic Flowable convention, not a masterclass one — so the starter can scan for `channelType: "kafka"` descriptors at test startup and derive the topic list automatically.

- `@EmbeddedFlowableKafka` replaces manual topic enumeration entirely.
- `KafkaTestBridge` wraps raw producer/consumer setup (currently duplicated as `publishPaymentCallback`/`consumeMessagesContaining` in `OrderProcessScenariosTest`) into two generic calls: `bridge.send(topic, key, json)` / `bridge.awaitMessage(topic, predicate, timeout)`.

### 4.3 Declarative HTTP mocking — WireMock, in-process, Docker-free, convention over configuration

**Decision: reuse WireMock's own mapping-file JSON format rather than invent a custom schema.** It's already "for this URL respond with this body and status," every WireMock user already knows it, and the starter doesn't need to build/maintain its own parser.

**Default flow needs no annotation and no Java code:**

```
src/test/resources/
└── httpmocks/
    └── payment-gateway/              ← folder name = service name
        ├── mappings/
        │   └── charge-success.json
        └── __files/                  ← optional, for large/binary response bodies
            └── charge-response.json
```

`mappings/charge-success.json` — plain WireMock stub, nothing starter-specific:

```json
{
  "request": {
    "method": "POST",
    "urlPath": "/v1/charge"
  },
  "response": {
    "status": 200,
    "jsonBody": { "status": "SUCCESS", "confirmationId": "PAY-{{randomValue length=8 type='ALPHANUMERIC'}}" },
    "headers": { "Content-Type": "application/json" }
  }
}
```

**What the starter does automatically at test context startup:**

1. `FlowableTestHttpStubAutoConfiguration` (`@ConditionalOnClass(WireMockServer.class)`) scans the configured root (`flowable.test.http-mocks.root`, default `classpath:httpmocks`) for immediate subdirectories.
2. For each subdirectory found, it starts one in-process WireMock server (random free port) pointed at that subdirectory as its `mappings`/`__files` root — WireMock reads its own format natively, no custom loading code.
3. It injects `<service-name>.base-url=http://localhost:{port}` into the Spring `Environment` (folder `payment-gateway` → property `payment-gateway.base-url`), so a consumer's `@ConfigurationProperties(prefix = "payment-gateway")`-bound REST client resolves it with **no test-side code at all**.

**The `@MockExternalService` annotation only exists for cases convention can't cover**, kept as small as possible:

```java
@MockExternalService(name = "payment-gateway", stubs = "classpath:httpmocks/payment-gateway-timeout")
```

Used only when one specific test needs *different* stubs than the shared default folder (e.g. simulating a gateway timeout just for a failure-path test) — still just pointing at another folder of plain JSON files, never inline Java stub-building.

**Why the native format, not a custom one:** the moment a "minimal" custom schema needs headers, query-param matching, request-body matching, multiple responses for the same URL (stateful/scenario stubs), or a delay/fault, every one of those is already a field in WireMock's own JSON schema (`headers`, `queryParameters`, `bodyPatterns`, `scenarioName`/`requiredScenarioState`, `fixedDelayMilliseconds`, `fault`) — the starter gets it for free by not translating. A bespoke schema would either stay too thin to be useful or slowly re-grow into WireMock's own format anyway.

**Decision: this repo's delegates are not changed to add an HTTP-calling example.** This project has no HTTP-calling delegate today (delegates are pure in-process Java + Kafka), so the HTTP-mocking capability is built and validated entirely within the starter's own test suite (a synthetic delegate + fake external service), not demonstrated in this repo.

### 4.4 Generic BPMN process assertions/harness

Extracts the boilerplate repeated across both existing test classes (`assertProcessInstanceEndedAt`, `findManagerTask`, task-completion patterns) into reusable, domain-blind primitives:

```java
assertThat(processInstance)
    .hasEndedAt("endEventCompleted");     // wraps HistoryService lookup

harness.completeSingleTask(processInstanceId, "managers", Map.of("approved", true));
harness.awaitCallActivityChild(processInstanceId, "refundProcess", Duration.ofSeconds(20));
```

`hasEndedAt`/`completeSingleTask` take activity IDs and candidate groups as plain strings — they know nothing about "Manager Approval" or refunds specifically.

### 4.5 Composed annotation

`@FlowableProcessTest` = `@SpringBootTest` + `@ActiveProfiles` (configurable) + conditionally imports whichever of 4.2/4.3 are on the classpath. One annotation replaces the `@SpringBootTest @ActiveProfiles("test") @EmbeddedKafka(...)` stack currently on `OrderProcessTest`.

## 5. Flowable engine detection & version isolation

The starter must never conflict with, or silently override, the consumer's own chosen Flowable version.

### 5.1 Core principle: the starter never bundles Flowable

In the starter's own `pom.xml` (both `flowable-test-core` and `flowable-test-autoconfigure` modules):

```xml
<dependency>
    <groupId>org.flowable</groupId>
    <artifactId>flowable-engine</artifactId>
    <version>${flowable.compileVersion}</version>  <!-- e.g. 7.1.0, only for compiling against the API -->
    <scope>provided</scope>
</dependency>
```

`provided` scope compiles the starter's code against `RuntimeService`/`TaskService`/`HistoryService`/`RepositoryService` but is **not transitive** — Maven never propagates it into a consumer's dependency tree. This is what actually prevents conflicts: without `provided`, Maven's "nearest wins" dependency mediation could let the starter's declared Flowable version silently outrank the consumer's own explicitly pinned version somewhere in the tree, producing a *runtime* version different from what the consumer thinks they're on — a worse failure mode than a hard error, because it's silent. `provided` removes the starter's declaration from that contest entirely; the consumer's own `flowable-spring-boot-starter` dependency is the sole source of the engine at both compile and runtime.

**Explicit non-goal: no shading/relocation.** Shading Flowable inside the starter jar is sometimes proposed as a conflict-avoidance trick, but it's wrong here specifically — the starter's autoconfiguration must accept the consumer's own `RuntimeService`/`TaskService` bean instances from the Spring context by their real type; relocated/renamed classes couldn't interoperate with those beans at all.

### 5.2 Detection — classpath + bean presence, ordered after Flowable's own autoconfiguration

```java
@AutoConfiguration(after = ProcessEngineAutoConfiguration.class)   // Flowable's own autoconfig class
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")   // classpath probe, no hard link
@ConditionalOnBean(ProcessEngine.class)                            // confirms Flowable's autoconfig actually ran
public class FlowableTestAutoConfiguration { ... }
```

The starter never constructs a `ProcessEngine` itself — it only activates once Flowable's own Spring Boot autoconfiguration has already produced one, and wires its DSL/harness beans (`ProcessTestHarness`, `KafkaTestBridge`, etc.) around the consumer's existing `RuntimeService`/`TaskService`/`HistoryService`/`RepositoryService` beans.

### 5.3 Fail-fast compatibility guard

Rather than let a version mismatch surface as an obscure `NoSuchMethodError` mid-test, the starter checks `ProcessEngine.VERSION` at context startup and fails immediately with an actionable message if it's outside the supported range:

```java
@Bean
InitializingBean flowableCompatibilityGuard(ProcessEngine engine) {
    return () -> {
        if (!SUPPORTED_RANGE.contains(ProcessEngine.VERSION)) {
            throw new IllegalStateException(
                "flowable-test-spring-boot-starter %s supports Flowable %s, but the consumer project " +
                "resolved Flowable %s. Align your org.flowable:flowable-spring-boot-starter version, " +
                "or use an older starter release — see the compatibility matrix in the README."
                .formatted(STARTER_VERSION, SUPPORTED_RANGE, ProcessEngine.VERSION));
        }
    };
}
```

If Flowable's own autoconfiguration never ran at all (no `ProcessEngine` bean — e.g. the consumer forgot `flowable-spring-boot-starter`), `@ConditionalOnBean` simply means the starter's test infrastructure never activates, rather than throwing at an unrelated point.

### 5.4 Supported version range: 7.x only

**Decision: `SUPPORTED_RANGE = [7.0.0, 8.0.0)`**, matching this repo's own Flowable 7.1.0 and Flowable's current release line. The starter's own build compiles against a fixed reference version (`7.1.0`) but the compatibility guard checks the *actual runtime* `ProcessEngine.VERSION`, so any 7.x release works, not just the exact compile-time version. The starter's own CI runs its test suite in a matrix across the 7.x releases it claims to support (at minimum the oldest and newest 7.x it supports) to keep that promise empirically verified rather than just asserted. If Flowable ships a breaking change to the four API interfaces the starter depends on, that's a starter major-version bump with an updated range — not silently absorbed.

### 5.5 Testing the starter itself (chicken-and-egg case)

The starter's own test suite (validating its own autoconfiguration end-to-end) needs a concrete, real Flowable engine to run against — an ordinary `test`-scope dependency in the starter's own POM, pinned to a real version:

```xml
<dependency>
    <groupId>org.flowable</groupId>
    <artifactId>flowable-spring-boot-starter</artifactId>
    <version>7.1.0</version>
    <scope>test</scope>
</dependency>
```

This never leaks to consumers — Maven `test` scope, like `provided`, isn't transitive. It's orthogonal to §5.1: `provided` governs what the *main* sourceset compiles against and exposes downstream (nothing), `test` scope is only for the starter's internal validation.

### 5.6 Explicit non-goals for v1

- No support for Flowable's CMMN or DMN engines — detection targets `ProcessEngine`/`RuntimeService` (BPMN) specifically.
- No multi-engine-instance support (a consumer running two separate `ProcessEngine` beans) — out of scope until a concrete need surfaces.
- No reflection-based multi-major-version adapter layer — unnecessary given the 7.x-only floor; revisit only if a 6.x consumer becomes a real requirement.

## 6. Migration plan for this repo (as reference consumer)

1. Build the starter in its own repo per §3–§5 above.
2. This project adds it as a `test`-scope dependency; drops `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-kafka`, `testcontainers-postgresql` from `pom.xml`.
3. `OrderProcessScenariosTest` is rewritten on `@FlowableProcessTest` + `KafkaTestBridge` + `ProcessTestHarness`, same 8 scenarios (S1–S8), no `@Testcontainers`/`@Container`/`@DynamicPropertySource`. DB provider stays H2 (default) since this project's JSON-as-text order-item column has no Postgres-specific fidelity need that would justify `embedded-postgres`.
4. `OrderProcessTest` either gets folded into the rewritten class or stays as-is, since its needs are already fully met by §4.2.

## 7. Open follow-ups

- No HTTP-calling delegate exists in this repo to exercise §4.3 end-to-end here; it stays validated only within the starter's own test suite unless a future scenario in this repo needs one.
- This document is design-only. Next steps are two separate `/sc:implement` efforts: (1) scaffold the starter repo itself, and (2) once published, the migration PR here that swaps Testcontainers out of `OrderProcessScenariosTest`.
