# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Docker-free Spring Boot **test** starter for Flowable BPMN projects: embedded DB, embedded Kafka
(topics auto-discovered from Flowable's own Kafka Event Registry `*.channel` files), declarative
HTTP mocking (plain WireMock mapping JSON), and a generic process-testing DSL/harness. It is a
library, not an application — every public API operates on generic Flowable primitives
(`RuntimeService`/`TaskService`/`HistoryService`, process instance IDs, activity IDs, candidate
groups) and must never reference any consumer-project-specific domain type.

The full design rationale (why `provided` scope, why WireMock's native format, why
`EnvironmentPostProcessor`/`ContextCustomizerFactory` instead of plain `@Bean`s, version-isolation
strategy) lives in `claudedocs/flowable-test-starter-design.md` — read it before changing module
boundaries or the Flowable version-compatibility mechanism.

## Build & test commands

Build from the repo root (Maven multi-module reactor):

```
mvn clean install
```

Run all tests (only `flowable-test-autoconfigure` has a test suite; it runs against a real, pinned
Flowable 7.1.0 engine declared as a `test`-scope dependency):

```
mvn test
```

Run a single test class:

```
mvn -pl flowable-test-autoconfigure test -Dtest=FlowableTestKafkaAutoConfigurationTest
```

Run a single test method:

```
mvn -pl flowable-test-autoconfigure test -Dtest=FlowableTestKafkaAutoConfigurationTest#methodName
```

Build a single module (and its dependencies via `-am`):

```
mvn -pl flowable-test-autoconfigure -am install
```

Java 21 is required (`maven.compiler.release=21` in the parent POM).

## Module architecture

Three-module Maven reactor mirroring Spring Boot's starter/autoconfigure split:

| Module | Role |
|---|---|
| `flowable-test-core` | Annotations (`@FlowableProcessTest`, `@MockExternalService`, `@EmbeddedFlowableKafka`), `ProcessTestHarness`, `ProcessInstanceAssert`, `KafkaTestBridge`, `HttpStubConfigurer` SPI. **No Spring Boot auto-configuration** — that's deliberately kept out of this module. |
| `flowable-test-autoconfigure` | One `@AutoConfiguration` class per capability, each `@ConditionalOnClass`-gated so a consumer only activates what's on their classpath. Also owns the `EnvironmentPostProcessor`s and `ContextCustomizerFactory`. **This is the only module with a test suite** — it validates every capability end-to-end against a real Flowable engine. |
| `flowable-test-starter` | Empty-POM aggregator. The only artifact consumers actually declare a dependency on; pulls in the two modules above and nothing else. |

### Auto-configuration classes (`flowable-test-autoconfigure`)

- `FlowableCompatibilityGuardAutoConfiguration` — runs at `HIGHEST_PRECEDENCE` among the starter's
  autoconfigs (`after` Flowable's own `ProcessEngineAutoConfiguration`). Checks the consumer's
  runtime `ProcessEngine.VERSION` against the supported range (`[7.0.0, 8.0.0)`, see
  `FlowableVersions`) and fails fast with an actionable message instead of a later
  `NoSuchMethodError`.
- `FlowableTestDatasourceAutoConfiguration` — always active; wires H2 (default) or
  `embedded-postgres` (opt-in via `flowable.test.datasource.provider`) as a plain `DataSource` bean.
- `FlowableTestAssertionsAutoConfiguration` — always active; registers the `ProcessTestHarness` bean
  once a `ProcessEngine` exists.
- `FlowableTestKafkaAutoConfiguration` — `@ConditionalOnClass(EmbeddedKafkaBroker.class)`.
- `FlowableTestHttpStubAutoConfiguration` — `@ConditionalOnClass(WireMockServer.class)`.

All of these gate on `@ConditionalOnBean(ProcessEngine.class)` — the starter never constructs a
`ProcessEngine` itself; it only wires around Flowable's own already-produced engine beans.

### Why `EnvironmentPostProcessor`, not `@Bean`

`FlowableTestKafkaEnvironmentPostProcessor` and `FlowableTestHttpStubEnvironmentPostProcessor` (in
`spring.factories`) run **before** `ApplicationContext` refresh, injecting
`spring.kafka.bootstrap-servers` / `<service-name>.base-url` into the `Environment`. This has to
happen at this stage — not in a regular `@Bean` method — because Spring Kafka's auto-configured
producer/consumer factories and `@ConfigurationProperties`-bound HTTP clients read these properties
*while they themselves are being created*, and same-context `@Bean` ordering can't guarantee the
starter's bean runs first. Both are `Ordered.LOWEST_PRECEDENCE` so `ConfigDataEnvironmentPostProcessor`
has already loaded `application.yml`/consumer overrides by the time they read `flowable.test.*`
properties. Per-test-class `@MockExternalService` overrides go through a separate mechanism
(`MockExternalServiceContextCustomizerFactory`/`MockExternalServiceContextCustomizer`) because that
needs Spring's *TestContext* framework, not the plain `SpringApplication` environment-processing
extension point.

### Kafka topic auto-discovery

`EventRegistryChannelScanner` scans `classpath*:**/*.channel` (configurable via
`flowable.test.kafka.channel-location`) for Flowable's own Kafka Event Registry descriptors,
extracting both the outbound `"topic"` and inbound `"topics"` shapes — so consumers never
hand-maintain an `@EmbeddedKafka(topics = {...})` list.

### HTTP mock auto-discovery

`HttpMockDiscovery` scans `classpath:httpmocks/<service-name>/mappings/*.json` (configurable via
`flowable.test.http-mocks.root`) for immediate subdirectories; each becomes one in-process WireMock
server, with `<service-name>.base-url` injected into the environment. Uses WireMock's own native
mapping JSON format rather than a custom schema — see design doc section 4.3 for why.

## Flowable version isolation (critical invariant)

Both `flowable-test-core` and `flowable-test-autoconfigure` declare `org.flowable:flowable-engine`
as `provided` scope, **never** `compile`/`runtime`. This is deliberate and load-bearing: `provided`
compiles the starter's code against Flowable's API but is not transitive, so the consumer's own
`org.flowable:flowable-spring-boot-starter` is always the sole source of the engine at runtime —
avoiding silent Maven "nearest wins" version mediation conflicts. Do not change this to `compile` or
add Flowable as a bundled/shaded dependency; see design doc section 5.1 for the full rationale
(including why shading specifically doesn't work here — the starter must interoperate with the
consumer's own `RuntimeService`/`TaskService` bean instances by their real type).

`flowable-test-autoconfigure` additionally declares `flowable-spring-boot-starter` as a `test`-scope
dependency, pinned to `${flowable.version}` (currently 7.1.0) — this is only for the module's own
internal validation test suite and never leaks to consumers (design doc section 5.5).

When bumping the supported Flowable range, update it in three places that must stay in sync:
`flowable.supported.min`/`flowable.supported.maxExclusive` in the root `pom.xml`,
`FlowableCompatibilityGuardAutoConfiguration.SUPPORTED_MIN_INCLUSIVE`/`SUPPORTED_MAX_EXCLUSIVE`, and
the README's compatibility table.

## Scope discipline

Nothing in this codebase may reference domain types from any specific consumer project (e.g. no
`Order`, `OrderProcessService`, or project-specific BPMN file names). If a capability can't be
expressed in terms of generic Flowable primitives, Kafka topic/key/value strings, or generic HTTP
stub definitions, it doesn't belong in this starter.

## Status

Freshly scaffolded — every capability has a passing internal validation test in
`flowable-test-autoconfigure`, but the starter has not yet been exercised against a real external
consumer project.
