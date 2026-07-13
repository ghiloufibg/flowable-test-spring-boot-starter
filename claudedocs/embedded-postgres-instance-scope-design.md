# Design: Configurable Instance Scope for Embedded Postgres

Date: 2026-07-13
Status: Proposed (design only — no implementation yet)
Addresses: finding #1 in `resource-efficiency-analysis.md`
Extends: main design doc section 4.1 ("Embedded DB — always on, pluggable fidelity")

## Problem

`EmbeddedPostgres` is currently wired as a plain `@Bean(destroyMethod = "close")` scoped to the Spring
`ApplicationContext` (`FlowableTestDatasourceAutoConfiguration.java:45-50`). Every `MergedContextConfiguration`
Spring's test-context cache can't reuse forks a brand-new native Postgres OS process — measured at
15–20s each. Kafka (`EmbeddedFlowableKafkaSupport`) and WireMock (`EmbeddedFlowableHttpMockSupport`)
avoid this entirely via a JVM-wide singleton, started at most once per JVM and reused across every
context.

## Goal

Let a consumer opt into the same JVM-wide-singleton pattern for embedded Postgres, **without changing
the default behavior** — existing consumers who never touch the new property see zero change: same
bean type, same per-context isolation, same lifecycle, same test outcomes.

## Non-goals

- Not changing anything about the `flowable.test.datasource.provider` (`auto`/`h2`/`embedded-postgres`)
  three-way choice — this design is purely about *how* the embedded-postgres provider allocates its
  server process, layered underneath that existing decision.
- Not attempting cross-JVM or cross-module sharing (e.g. across a multi-module Maven reactor's separate
  Surefire forks) — scope is exactly what Kafka/WireMock already do: one JVM, one shared instance.
- Not changing H2's behavior at all — H2 already gets fresh, cheap, per-context isolation for free via
  Spring Boot's own `generate-unique-name` default (confirmed: `DataSourceProperties.generateUniqueName`
  defaults to `true`), so there's nothing to optimize there.

## Configuration surface

New property, nested under the existing `flowable.test.datasource.*` namespace (matches the existing
nesting style of `flowable.test.kafka.*` / `flowable.test.http-mocks.*`):

```yaml
flowable:
  test:
    datasource:
      provider: auto                        # unchanged: auto | h2 | embedded-postgres
      embedded-postgres:
        instance-scope: per-context          # NEW. per-context (default) | shared
```

| Value | Behavior |
|---|---|
| `per-context` (default) | **Unchanged.** A fresh `EmbeddedPostgres` process is forked for every Spring context that activates the embedded-postgres provider, torn down when that context closes. Identical to today's code. |
| `shared` | One `EmbeddedPostgres` server process is started at most once per JVM. Each Spring context gets its own logical database on that shared server (own schema/tables/data — see Isolation below), avoiding the process-fork cost for every context after the first. |

Invalid values throw `IllegalStateException` at condition-evaluation time, mirroring
`EmbeddedPostgresPreferredCondition`'s existing behavior for the `provider` property.

## Component design

### `EmbeddedPostgresInstanceScopeCondition` (new, alongside `EmbeddedPostgresPreferredCondition`)

Reads `flowable.test.datasource.embedded-postgres.instance-scope`, exposes a simple
`static boolean isShared(Environment)` used by the two bean methods below to pick a code path. Same
shape as the existing `EmbeddedPostgresPreferredCondition` — a small `Condition`/helper, not a new
abstraction layer.

### `EmbeddedPostgresSupport` (new, package-private, mirrors `EmbeddedFlowableKafkaSupport`)

```java
final class EmbeddedPostgresSupport {

  private static final Object LOCK = new Object();
  private static volatile EmbeddedPostgres server;
  private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

  private EmbeddedPostgresSupport() {}

  static EmbeddedPostgres sharedServer() {
    // double-checked locking, JVM shutdown hook registered exactly once —
    // identical structure to EmbeddedFlowableKafkaSupport.startIfNeeded
  }

  static DataSource freshDatabase(EmbeddedPostgres server) {
    final String databaseName = "flowable_test_" + DATABASE_SEQUENCE.incrementAndGet();
    // 1. connect to the server's default maintenance database
    // 2. execute CREATE DATABASE <databaseName>
    // 3. return server.getDatabase(<user>, databaseName)
  }
}
```

Two responsibilities, deliberately split the same way `EmbeddedFlowableKafkaSupport` separates
"start the broker" from "add topics to it":
- `sharedServer()` — lazy, JVM-wide, at-most-once start + shutdown hook (copy of the existing
  double-checked-locking pattern already proven correct for Kafka).
- `freshDatabase(...)` — cheap, called once per Spring context, provisions an isolated logical database
  on the already-running server via `CREATE DATABASE` (milliseconds) rather than forking a process.

**Open implementation question** (flagging now so it isn't a surprise at implementation time):
`EmbeddedPostgres.getDatabase(user, dbName)` (confirmed present in `embedded-postgres:2.0.7`'s API via
bytecode inspection) returns a `DataSource` for an existing database — it does not itself run `CREATE
DATABASE`. `freshDatabase(...)` needs an explicit provisioning step (a short-lived JDBC connection to
the server's default `postgres` database, `CREATE DATABASE "<name>"`) before calling `getDatabase(...)`.
This should be verified against the actual zonky API surface during implementation, not assumed further
than the design level here.

### `FlowableTestDatasourceAutoConfiguration` changes

The existing two `@Bean` methods stay, gated exactly as today by `@ConditionalOnClass(EmbeddedPostgres.class)`
and `@Conditional(EmbeddedPostgresPreferredCondition.class)`. Each one branches internally on the new
scope condition:

```java
@Bean(destroyMethod = "close")
@ConditionalOnClass(EmbeddedPostgres.class)
@Conditional({EmbeddedPostgresPreferredCondition.class, PerContextScopeCondition.class})
EmbeddedPostgres embeddedPostgres() throws IOException {
  return EmbeddedPostgres.builder().start();           // unchanged today's behavior
}

@Bean(destroyMethod = "")                               // no per-context teardown — see Lifecycle below
@ConditionalOnClass(EmbeddedPostgres.class)
@Conditional({EmbeddedPostgresPreferredCondition.class, SharedScopeCondition.class})
DataSource embeddedPostgresSharedDataSource() {
  final EmbeddedPostgres server = EmbeddedPostgresSupport.sharedServer();
  return EmbeddedPostgresSupport.freshDatabase(server);
}
```

Splitting into two mutually-exclusive `@Conditional` bean definitions (rather than one method with an
`if`) keeps `per-context` mode's bean definition byte-for-byte what it is today — the safest way to
guarantee the default path is genuinely unchanged, not just behaviorally equivalent.

`embeddedPostgresDataSource(EmbeddedPostgres)` (the existing bean that calls
`embeddedPostgres.getPostgresDatabase()`) stays as-is and only activates in `per-context` mode; `shared`
mode's `DataSource` bean is self-contained (built directly in `embeddedPostgresSharedDataSource()`), so
there's no third bean needed.

## Isolation model

This is the question raised earlier in this conversation, and the design preserves the answer given
then: **the per-context isolation boundary does not change.** In both modes, every Spring context gets
its own logical database — a distinct schema/tables/data, never shared with another context. What
changes is only whether that database lives on a freshly-forked process (`per-context`) or as one of
several databases on a shared process (`shared`). Two contexts that are already cache-shared today
(identical `@FlowableProcessTest` config) already share database state today, in both modes, exactly
as they do with H2 — that's inherent to Spring's `TestContextCache`, not something this design
introduces or changes.

**New consideration introduced by `shared` mode**: connection/IO/CPU contention on one process instead
of N independent processes, relevant only if Surefire is ever configured for parallel test execution
within one JVM (today it isn't — no `forkCount`/`parallel` config exists in either pom). Document this
explicitly in the property's javadoc so a consumer enabling both `shared` mode and parallel Surefire
execution later understands the tradeoff instead of discovering it as a mystery slowdown.

## Lifecycle & shutdown

`shared` mode's `DataSource` bean uses `destroyMethod = ""`, exactly like the Kafka broker bean — but
**this design explicitly avoids the bug found in finding #2** (`EmbeddedKafkaBroker` implements
`DisposableBean` directly, so `destroyMethod = ""` doesn't actually suppress Spring's interface-based
destroy call). The `DataSource` returned by `EmbeddedPostgresSupport.freshDatabase(...)` is a plain
JDBC `DataSource` (not `Closeable`/`DisposableBean`), so Spring has nothing to call on context close
regardless of `destroyMethod` — the shared server's actual teardown is owned solely by the one JVM
shutdown hook registered in `EmbeddedPostgresSupport.sharedServer()`, the same single-owner pattern
already proven correct for WireMock (`EmbeddedFlowableHttpMockSupport`) and (once finding #2 is fixed)
intended for Kafka.

Per-context databases are not explicitly dropped when their context closes — same as Kafka topics and
WireMock stub folders, which also aren't cleaned up per-context today. Acceptable for the same reason:
a CI test run is a bounded process; there's no long-lived server accumulating garbage between separate
runs.

## Backward compatibility

- Default value: `per-context`. A consumer who never sets the new property gets byte-for-byte the
  current bean definition and behavior.
- No change to `flowable.test.datasource.provider`'s three existing values or `EmbeddedPostgresPreferredCondition`.
- No change to H2 mode.
- The property is a no-op (never read/evaluated) unless the embedded-postgres provider is actually
  selected — same short-circuit already implied by `@ConditionalOnClass(EmbeddedPostgres.class)` and
  `@Conditional(EmbeddedPostgresPreferredCondition.class)` gating the whole bean pair.

## Test plan (design-level)

- Existing `FlowableTestDatasourceAutoConfigurationTest` nested classes stay unchanged — they exercise
  `per-context` mode (the default), same assertions, same expected timings.
- New nested class(es) for `shared` mode:
  - Two separate contexts (distinct `properties`) both requesting `embedded-postgres` +
    `instance-scope=shared`; assert each resolves `SELECT current_database()` to a *different* name —
    directly verifies the isolation claim above, not just the performance claim.
  - Assert only one native process/one `EmbeddedPostgres.start()` occurred across both contexts — via a
    package-private test accessor on `EmbeddedPostgresSupport` (e.g. a start-count counter), same idea
    as could be added for `EmbeddedFlowableKafkaSupport` if it needed the same proof.
  - A timing assertion mirroring the evidence already gathered in `resource-efficiency-analysis.md`:
    the second context's setup should complete in a small fraction of the first context's time (no
    hard-coded absolute threshold — assert relative speedup to avoid CI-environment flakiness).

## Documentation updates required at implementation time

- README compatibility/config table: add `flowable.test.datasource.embedded-postgres.instance-scope`.
- Main design doc section 4.1: cross-reference this file, same way other addendum decisions are
  expected to fold back into the design doc per `CLAUDE.md`'s existing convention.
