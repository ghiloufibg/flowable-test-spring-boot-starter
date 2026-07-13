# Resource Efficiency & Correctness Analysis

Date: 2026-07-13
Scope: `flowable-test-core` and `flowable-test-autoconfigure` (every source file), verified against the
design doc's stated rationale plus empirical measurement (`mvn test` timings) and bytecode inspection
where a claim needed proof rather than assumption. Answers the question: is the starter itself
resource-efficient and optimized for a *consumer's* CI/integration testing — not just this repo's own
internal validation suite.

## 1. `EmbeddedPostgres` is not a JVM-wide singleton, unlike Kafka/WireMock — primary gap

**File**: `flowable-test-autoconfigure/src/main/java/com/flowabletest/autoconfigure/datasource/FlowableTestDatasourceAutoConfiguration.java:45-58`

Kafka (`EmbeddedFlowableKafkaSupport`) and WireMock (`EmbeddedFlowableHttpMockSupport`) are both started
at most once per JVM and reused across every Spring context a consumer's suite creates. `EmbeddedPostgres`
is wired as a plain `@Bean(destroyMethod = "close")`, scoped to the Spring context. Any context Spring's
test-context cache can't reuse (different `@TestPropertySource`, `@MockBean`, `@ActiveProfiles`, etc.)
forks a brand-new native Postgres OS process.

**Measured proof**: running this module's own suite (`mvn -pl flowable-test-autoconfigure test`, clean,
single JVM), two contexts differing only by one property forked two separate Postgres processes:

```
AutoModeWithEmbeddedPostgresOnTheClasspath:   17.29s
ExplicitEmbeddedPostgresProvider:             15.18s
ExplicitH2ProviderOverridesAutoDetection:      1.70s   (no Postgres fork)
```

vs. every other test class in the suite (Kafka, WireMock, ProcessTestHarness) completing in 0.6–11s
total, benefiting from singleton/context-cache reuse.

**Consumer impact**: this cost pattern reproduces in any downstream project — every distinct
`@SpringBootTest` config using `embedded-postgres` mode pays a ~15–20s process-fork tax.
`embedded-postgres:2.0.7` (already a dependency) exposes `EmbeddedPostgres.getDatabase(user, dbName)`,
confirmed present via bytecode inspection of the jar, which creates a fresh logical database on an
already-running instance (`CREATE DATABASE`, milliseconds) — the same JVM-singleton-plus-per-context-
namespace pattern already used correctly for Kafka topics and WireMock mapping folders. This is the
highest-leverage fix in the codebase.

**CI compounding**: `.github/workflows/ci.yml:31` runs the full suite twice (Flowable 7.0.0 and 7.1.0
matrix legs), each via `mvn clean test` — so this ~32s tax is paid twice per CI run, ~64s of total CI
wall time.

**Suggested fix**: introduce an `EmbeddedPostgresSupport` singleton mirroring
`EmbeddedFlowableKafkaSupport`/`EmbeddedFlowableHttpMockSupport` — one `EmbeddedPostgres` server per JVM,
handing each new Spring context its own database via `getDatabase(user, dbName)` instead of forking a new
process. Preserves schema/data isolation between test classes while cutting the dominant cost.

## 2. Embedded Kafka broker is destroyed twice on JVM shutdown — confirmed bug, not just log noise

**Files**: `kafka/FlowableTestKafkaAutoConfiguration.java:31`, `kafka/EmbeddedFlowableKafkaSupport.java:36-37`

`FlowableTestKafkaAutoConfiguration` sets `@Bean(destroyMethod = "")` specifically to stop Spring from
calling `.destroy()` on the broker a second time (its own javadoc explains this exact concern). It
doesn't work: bytecode inspection of `spring-kafka-test:3.2.4` shows —

```
EmbeddedKafkaBroker extends InitializingBean, DisposableBean
EmbeddedKafkaKraftBroker implements EmbeddedKafkaBroker, overrides destroy()
```

`destroyMethod = ""` only disables Spring's *inferred* destroy-method detection; it has no effect on a
bean that implements `DisposableBean` directly — Spring's `DisposableBeanAdapter` always invokes the
interface method on context close, independent of `destroyMethod`. So the broker is destroyed twice:
once via Spring's own context-close (interface call), once via the explicit
`Runtime.getRuntime().addShutdownHook(...)` in `EmbeddedFlowableKafkaSupport`. Reproduced in every test
run executed during this analysis; every run logs:

```
RejectedExecutionException: Task ... rejected from ThreadPoolExecutor@...[Terminated, ... completed tasks = 7]
IllegalStateException: Failed to shut down embedded Kafka cluster
```

Not a wall-clock cost, but a real defect in the documented double-teardown prevention, firing
unconditionally on every consumer's test run that uses this starter's Kafka capability.

**Suggested fix**: make the raw shutdown hook idempotent (guard against a second `destroy()` call), or
stop exposing the broker as a bean whose declared type implements `DisposableBean` directly (e.g. wrap
it).

## 3. Classpath scanning re-runs per uncached Spring context

**Files**: `kafka/FlowableTestKafkaEnvironmentPostProcessor.java:48-54`,
`http/FlowableTestHttpStubEnvironmentPostProcessor.java:50-53`

The "already processed" guard (`environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)`) is
per-`Environment`, i.e. per Spring context — not JVM-wide. The underlying broker/servers correctly aren't
restarted (guarded separately in the `*Support` singletons), but the `classpath*:**/*.channel` and
`classpath*:httpmocks/*/mappings/**` resource-pattern scans re-execute in full for every new, uncached
context. Minor relative to finding #1, but scales with classpath size (all jars on the consumer's
classpath) and number of distinct contexts, and compounds with the same context-cache-busting scenarios
described above.

## What's already good (verified, not assumed)

- Kafka broker and WireMock servers: genuinely well-designed JVM-wide singletons, each with a clear
  javadoc rationale, empirically confirmed not to be re-created across contexts.
- `EmbeddedFlowableKafkaContextCustomizer` / `MockExternalServiceContextCustomizer`: implemented as
  records specifically so they participate correctly in Spring's `MergedContextConfiguration` cache-key
  equality — deliberate and correct.
- `FlowableCompatibilityGuardAutoConfiguration`, `FlowableVersions`, `ProcessTestHarness`,
  `KafkaTestBridge`, `ProcessInstanceAssert`: no performance or correctness issues found; polling/blocking
  patterns are appropriate for a test harness.

## Net assessment

The starter's Kafka/WireMock story is efficient by design. The Postgres story is not — it's the one place
the "one shared resource, many lightweight contexts" pattern wasn't applied, and it's the dominant cost
in both this repo's own CI and any consumer project using `embedded-postgres` mode with more than one
Spring test configuration.
