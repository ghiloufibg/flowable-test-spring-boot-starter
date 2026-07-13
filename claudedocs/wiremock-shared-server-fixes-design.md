# Design: Fixing WireMock Shared-Server Issues #1 and #2

Date: 2026-07-13
Status: Implemented (2026-07-13) — see `EmbeddedFlowableHttpMockSupport`,
`HttpMockServiceRegistry`, and `FlowableTestHttpStubAutoConfiguration` in
`flowable-test-autoconfigure`
Addresses: findings #1 and #2 from the review that produced
`claudedocs/wiremock-shared-server-per-class-stubs.md`

## Problem restatement

**#1 — `HttpMockServers` bean can resolve the wrong server inside an overriding test class.**
`FlowableTestHttpStubAutoConfiguration.httpMockServers(Environment)` (`FlowableTestHttpStubAutoConfiguration.java:27-45`)
builds its map entirely from `flowable.test.http-mocks.discovered`, a property set only by
`FlowableTestHttpStubEnvironmentPostProcessor`'s classpath-wide default-folder scan. It has no
knowledge of `@MockExternalService` overrides applied by `MockExternalServiceContextCustomizer`
(`MockExternalServiceContextCustomizer.java:34-51`), which sets `<name>.base-url` but never touches
`discovered`. Result: `environment.getProperty("<name>.base-url")` and
`httpMockServers.get("<name>")` can point at two different WireMock server instances inside the same
overriding test class.

**#2 — WireMock servers accumulate unbounded for the life of the JVM.**
`EmbeddedFlowableHttpMockSupport.SERVERS` (`EmbeddedFlowableHttpMockSupport.java:20`) is a static map
populated via `computeIfAbsent`, never evicted. Every distinct `(name, location)` pair a suite ever
exercises leaves one real OS port bound for the rest of the JVM — unbounded growth as the number of
distinct `@MockExternalService(stubs = ...)` variants in a suite grows.

## Goals

- Fix #1 without changing behavior for the common case (no `@MockExternalService` anywhere) at all.
- Fix #2 by tying server lifetime to *actual usage* (reference counting across the Spring contexts that
  use a given `(name, location)` pair), not a size cap or time-based eviction that could stop a server
  a still-cached context depends on.
- Avoid repeating the mistake already found in the Kafka broker's shutdown handling (double-destroy
  when both a JVM shutdown hook and Spring's own lifecycle can call `.stop()`/`.destroy()`) — this
  design must not introduce the same class of bug for WireMock servers now that they can also be
  stopped mid-JVM-lifetime.
- No change to the public `HttpMockServers` record shape in `flowable-test-core` — keep the fix inside
  `flowable-test-autoconfigure`, which owns both the bug and the mechanism.

## Non-goals

- Not changing the sharing key (`name + "|" + classpathLocation`) — that's the correct unit of sharing,
  established in the earlier review.
- Not addressing findings #3–#5 (fail-fast guidance, `HttpStubConfigurer` mutation risk, typo'd `stubs`
  path) — out of scope per this design's requested pair.

## Component design

### 1. `EmbeddedFlowableHttpMockSupport` — split "start" from "track usage"

Current API: one method, `startIfNeeded(name, location)`, called by both the environment post-processor
(pre-refresh, unconditional default scan) and the context customizer (pre-refresh, override). Both call
sites only need "make sure the process is running" at that point — neither yet knows whether an
override running later in the same context-preparation phase will supersede a given name. If refcounting
were tied to these raw call sites, a name that gets overridden would still have its *default* variant
counted (incremented) with no corresponding release — a smaller-scoped recurrence of the exact leak
being fixed. So the API splits into two concerns:

```java
final class EmbeddedFlowableHttpMockSupport {

  // Unchanged responsibility, renamed for clarity: idempotently ensures a server for
  // (name, location) is running. No refcount side effect. Called pre-refresh by the
  // environment post-processor and the context customizer, exactly as startIfNeeded() is today.
  static WireMockServer ensureStarted(String name, String classpathLocation) { ... }

  // NEW. Refcount++ for (name, location); assumes ensureStarted() already ran (defensively
  // calls it if not — cheap due to idempotency). Called exactly once per context, from the
  // httpMockServers bean, for each entry in that context's FINAL resolved (override-applied) map.
  static WireMockServer retain(String name, String classpathLocation) { ... }

  // NEW. Refcount--. At zero: stop the server, cancel its shutdown hook, remove it from the
  // map. Called exactly once per context, from a ContextClosedEvent listener, for the same
  // final resolved map retain() was called with.
  static void release(String name, String classpathLocation) { ... }
}
```

Internal state, in addition to the existing `SERVERS` map:

```java
private static final Map<String, AtomicInteger> REF_COUNTS = new ConcurrentHashMap<>();
private static final Map<String, Thread> SHUTDOWN_HOOKS = new ConcurrentHashMap<>();
```

`release()`'s zero-refcount path must, in order: (1) mark the server as stopped via an explicit
state flag rather than relying on WireMock's own idempotency, (2) call `server.stop()`, (3) call
`Runtime.getRuntime().removeShutdownHook(hooksThread)` wrapped in a try/catch that swallows
`IllegalStateException` (thrown if the JVM is already mid-shutdown, in which case the hook will run
harmlessly against the now-flagged-stopped server), (4) remove both map entries. This explicit
stopped-flag is the direct lesson from the Kafka `DisposableBean` double-destroy bug: don't assume the
underlying library's `.stop()`/`.destroy()` is safe to call twice — guard it ourselves.

### 2. `HttpMockServiceRegistry` — new, shared resolver (fixes #1's root cause)

A small new class in `flowable-test-autoconfigure/.../http`, with one responsibility: compute "what is
this context's final, override-applied set of `(name -> location)` mappings," reusable by both the bean
that exposes them and the listener that releases them.

```java
final class HttpMockServiceRegistry {

  // Merges flowable.test.http-mocks.discovered (defaults, set by the environment
  // post-processor) with flowable.test.http-mocks.overridden (per-context overrides, set by
  // MockExternalServiceContextCustomizer) — override wins per name. Same comma-encoded
  // "name=location,name=location" format already used for `discovered`.
  static Map<String, String> resolve(Environment environment) { ... }
}
```

### 3. `MockExternalServiceContextCustomizer` — add the missing property (fixes #1)

Alongside the existing `<name>.base-url` properties it already injects
(`MockExternalServiceContextCustomizer.java:37-50`), also add:

```java
properties.put(
    "flowable.test.http-mocks.overridden",
    overrides.stream()
        .map(o -> o.name() + "=" + resolvedLocation(o))
        .collect(Collectors.joining(",")));
```

using the same `addFirst(new MapPropertySource(...))` call already present — one extra key in the same
map, not a new mechanism.

### 4. `FlowableTestHttpStubAutoConfiguration` — consume the registry, add the release listener

```java
@Bean
@ConditionalOnMissingBean
HttpMockServers httpMockServers(Environment environment) {
  final Map<String, String> resolved = HttpMockServiceRegistry.resolve(environment);
  final Map<String, WireMockServer> servers = new LinkedHashMap<>();
  resolved.forEach((name, location) ->
      servers.put(name, EmbeddedFlowableHttpMockSupport.retain(name, location)));
  return new HttpMockServers(servers);
}

@Bean
ApplicationListener<ContextClosedEvent> httpMockServersReleaseListener(Environment environment) {
  final Map<String, String> resolved = HttpMockServiceRegistry.resolve(environment);
  return event -> resolved.forEach(EmbeddedFlowableHttpMockSupport::release);
}
```

Resolving `HttpMockServiceRegistry.resolve(environment)` once and capturing it in the listener's closure
(rather than re-resolving at close time) guarantees retain and release operate on exactly the same set,
even if environment property sources were mutated later by something else during the context's lifetime.

`FlowableTestHttpStubEnvironmentPostProcessor` and `MockExternalServiceContextCustomizer` switch their
existing `startIfNeeded(...)` calls to `ensureStarted(...)` — same call sites, same timing (pre-refresh),
just no longer touching the refcount directly.

## Walkthrough

**Default-only case (today's common path, must stay unchanged in outcome):**
1. Environment post-processor scans, finds `demo-service -> httpmocks/demo-service`, calls
   `ensureStarted("demo-service", "httpmocks/demo-service")` — server starts, sets
   `demo-service.base-url`, sets `flowable.test.http-mocks.discovered`.
2. Context refreshes. `httpMockServers` bean resolves the registry (`discovered` only, no
   `overridden`) → `{demo-service: httpmocks/demo-service}` → calls `retain(...)` → refcount = 1.
   `httpMockServers.get("demo-service")` now correctly returns the same server the base-url property
   points at — no behavior change from today for this case, since there was never a divergence here.
3. Context closes (JVM shutdown, or Spring's context cache evicting it) → release listener fires →
   refcount → 0 → server stopped, port freed.

**Override case (fixes #1 and #2 together):**
1. Environment post-processor still unconditionally finds and starts the *default*
   `demo-service -> httpmocks/demo-service` server (it doesn't know about the override yet — this is
   unavoidable given it runs first) — `ensureStarted` only, no retain, so no refcount side effect.
2. `MockExternalServiceContextCustomizer` runs next (still pre-refresh), starts/ensures the *alt*
   `demo-service -> httpmocks-alt/demo-service` server, overwrites `demo-service.base-url` to point at
   it, and sets `flowable.test.http-mocks.overridden = demo-service=httpmocks-alt/demo-service`.
3. Context refreshes. `httpMockServers` bean resolves the registry: `discovered` says
   `demo-service -> httpmocks/demo-service`, `overridden` says `demo-service -> httpmocks-alt/demo-service`
   — override wins → final map is `{demo-service: httpmocks-alt/demo-service}`. `retain(...)` is called
   only for the alt pair. **This is the fix for #1**: `httpMockServers.get("demo-service")` now returns
   the same alt server `demo-service.base-url` points at.
4. The shadowed default server (`demo-service -> httpmocks/demo-service`) was `ensureStarted` but never
   `retain`ed by this context — refcount stays at whatever other contexts hold it (0 if no other context
   uses the plain default), so it's *not* artificially kept alive by this context, and *not* leaked
   either.
5. Context closes → release listener releases exactly `{demo-service: httpmocks-alt/demo-service}` →
   refcount → 0 → alt server stopped. **This is the fix for #2**: nothing about this override run leaves
   a permanently-running extra server behind.

## Interaction with Spring's test-context cache (why this bounds growth, not just "eventually")

Spring's `TestContextCache` has a default size limit (`spring.test.context.cache.maxSize`, 32) and
evicts+closes the least-recently-used context once exceeded — firing `ContextClosedEvent` well before
JVM shutdown in large suites. Since release is wired to that same event, WireMock server accumulation is
naturally bounded by however many *distinct contexts* Spring itself is willing to keep cached
concurrently, not by the total number of distinct `(name, location)` pairs a suite exercises over its
full run. This is a direct, free consequence of hooking the existing Spring lifecycle event rather than
inventing a separate cache/TTL policy.

## Backward compatibility

- No property renames; `flowable.test.http-mocks.discovered` keeps its existing name and meaning.
  `flowable.test.http-mocks.overridden` is additive.
- `HttpMockServers` (public type in `flowable-test-core`) is unchanged.
- For any consumer never using `@MockExternalService`, behavior is identical to today except that
  servers are now correctly stopped when their last referencing context closes instead of living for
  the whole JVM — an efficiency improvement, not an observable behavior change (nothing in the current
  API lets a consumer depend on "the server outlives its context").

## Test plan (design-level)

- New assertion in a `MockExternalServiceOverrideTest`-shaped test: `httpMockServers.get("demo-service")`
  inside the overriding context serves the alt response (`"hi from ALT demo-service"`), not the default
  — directly verifies fix #1 in the way the existing test's `Environment`-only assertions do not.
- New test verifying fix #2: after a context using an override-only `(name, location)` pair closes
  (trigger via `@DirtiesContext` or by exceeding the context cache size), assert the corresponding
  server is no longer present/running — needs a package-private test accessor on
  `EmbeddedFlowableHttpMockSupport` exposing current refcount/liveness for a key, same idea already
  proposed for `EmbeddedPostgresSupport`'s test plan in the embedded-postgres design doc.
- Regression test: two contexts sharing the same *default* `(name, location)` pair — assert the server
  survives the first context's close (refcount still > 0) and is only stopped after the second (last)
  context closes — proves sharing for the common case isn't broken by the refcounting change.

## Documentation updates required at implementation time

- README / design doc section 4.3: note that `HttpMockServers` now reflects `@MockExternalService`
  overrides correctly, and that server lifetime is now tied to referencing-context lifetime rather than
  the whole JVM.
