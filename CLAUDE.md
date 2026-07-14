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

When bumping the supported Flowable range, update it in four places that must stay in sync:
`flowable.supported.min`/`flowable.supported.maxExclusive` in the root `pom.xml`,
`FlowableCompatibilityGuardAutoConfiguration.SUPPORTED_MIN_INCLUSIVE`/`SUPPORTED_MAX_EXCLUSIVE`, the
README's compatibility table, and the `flowable-version` matrix in
`.github/workflows/ci.yml` (oldest + newest supported release, design doc section 5.4) — CI runs
the full test suite once per matrix entry via `mvn test -Dflowable.version=<version>`, which
overrides the same `flowable.version` property used for both the `provided`-scope compile
dependency and the module's own `test`-scope validation engine, so each entry proves the range is
real rather than just asserted.

## Scope discipline

Nothing in this codebase may reference domain types from any specific consumer project (e.g. no
`Order`, `OrderProcessService`, or project-specific BPMN file names). If a capability can't be
expressed in terms of generic Flowable primitives, Kafka topic/key/value strings, or generic HTTP
stub definitions, it doesn't belong in this starter.

## Status

Freshly scaffolded — every capability has a passing internal validation test in
`flowable-test-autoconfigure`, but the starter has not yet been exercised against a real external
consumer project.

## 🎯 Code Quality Standards

### CRITICAL: Java 21 LTS - Project Standard

**This project uses Java 21 LTS exclusively.**

All generated code MUST use modern Java 21 features:
- Records for immutable data structures
- Pattern matching for instanceof and switch
- Text blocks for multi-line strings
- Sealed classes for restricted hierarchies
- Virtual threads for concurrency
- Enhanced switch expressions
- Local variable type inference (var) where it improves readability

**NO Java 22+ only features** - maintain Java 21 LTS compatibility

### 1. CLARITY
Code must be self-explanatory with clear intent:
- Use descriptive names for classes, methods, and variables
- Code should read like well-written prose
- Intent should be immediately obvious without documentation

### 2. CLEANLINESS
Follow clean code principles:
- Single Responsibility Principle for classes and methods
- No code duplication (DRY principle)
- Proper separation of concerns
- Consistent formatting and style

### 3. READABILITY
Code must be easy to read and understand:
- Use meaningful variable and method names
- Keep methods short and focused (ideally under 20 lines)
- Proper indentation and spacing
- Clear control flow without deeply nested structures

**CRITICAL: No Fully Qualified Names (FQN)**
- **NEVER use fully qualified class names in code** - this is ugly and reduces readability
- Always add proper import statements at the top of the file instead
- This applies to ALL code: production, tests, annotations, and configuration

**Prohibited Examples:**
```java
// ❌ WRONG - Ugly FQN usage
java.util.List<String> items = new java.util.ArrayList<>();
reactor.core.publisher.Flux<Data> flux = reactor.core.publisher.Flux.empty();
@SpringBootTest(classes = {com.example.config.AppConfig.class})
new com.example.service.MyService();

// ✅ CORRECT - Use imports
import java.util.List;
import java.util.ArrayList;
import reactor.core.publisher.Flux;
import com.example.config.AppConfig;
import com.example.service.MyService;

List<String> items = new ArrayList<>();
Flux<Data> flux = Flux.empty();
@SpringBootTest(classes = {AppConfig.class})
new MyService();
```

**Exception:** Only use FQN to resolve naming conflicts between different packages:
```java
// ✅ Acceptable - Resolving naming conflict
import java.util.Date;
java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
```

**Enforcement:**
- Review all code for FQN before committing
- Check annotations (especially `@SpringBootTest`, `@Import`, etc.)
- Check object instantiations and static method calls
- Check generic type parameters

### 4. PRODUCTION-READY
Code must be robust and maintainable:
- Proper error handling and validation
- No hardcoded values or magic numbers
- Consider edge cases
- Thread-safe where applicable
- Performance-conscious but favor clarity over premature optimization

### 5. ENCAPSULATION & IMMUTABILITY
Enforce by default whenever possible:
- All class fields should be `private final` where possible
- All constructors should be marked `final` (implicit for non-abstract classes)
- All local variables should be marked `final`
- Prefer immutable data structures (records, List.of(), Set.of(), Map.of())
- Use defensive copying when returning mutable objects

**EXCEPTION: Spring Configuration Classes**
- Classes annotated with `@Configuration` MUST NOT be marked `final`
- Spring requires non-final classes to create CGLIB proxies for bean methods
- This applies to: `@Configuration`, `@ConfigurationProperties`
- Example: `public class DangerousPatternsConfig` (NOT `public final class`)

### 6. JAVADOC MUST BE SELF-CONTAINED — NO REFERENCES TO `claudedocs/`

**CRITICAL: Javadoc comments must never cite the internal `claudedocs/*.md` design files or
"design doc section X.Y".**

The `claudedocs/` design docs are internal working notes for development and will be **deleted**
once the project stabilizes (see `## Status`). A Javadoc that points a future reader at
`claudedocs/bpmn-failure-diagnostics-design.md` or "design doc section 4.3" becomes a dead link the
moment that file is removed. Every Javadoc comment must carry its own rationale inline — write the
*why*, not a pointer to where the *why* is written elsewhere — exactly like any professional Spring
project's public API documentation.

**Prohibited Examples:**
```java
// ❌ WRONG - dangling reference that breaks once claudedocs/ is deleted
/**
 * Starts one WireMock server per discovered service (design doc section 4.3).
 * See {@code claudedocs/http-mock-explicit-service-registry-design.md} for the full rationale.
 */

// ✅ CORRECT - rationale stated inline, no external pointer
/**
 * Starts one WireMock server per discovered service. {@code
 * flowable.test.http-mocks.services} (optional) replaces the classpath scan with an explicit,
 * declared list of service names; absent, every immediate subfolder under {@code root} is
 * discovered and started.
 */
```

**Enforcement:**
- Before committing, grep new/changed Javadoc for `claudedocs`, `design doc`, `design document`,
  and `design:` — none of these strings may appear in a `.java` file's `/** ... */` comments.
- This rule applies only to Javadoc/code comments. Prose files under `claudedocs/` themselves, and
  this `CLAUDE.md`, may still reference each other freely — they are removed together.
