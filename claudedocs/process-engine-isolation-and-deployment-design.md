# Process-engine test isolation and deployment-allow-list design

Merges two previously separate documents (`process-engine-test-isolation-design.md`,
`process-deployment-allow-list-design.md`) into one, since both extend the same annotation, touch
the same two modules, and compose directly — Part C below covers exactly how they interact.

Relates to: `HttpMockDiscovery`/`http-mock-explicit-service-registry-design.md` (the mechanism Part B
mirrors), `EventRegistryChannelScanner`.

## 0. Combined `@FlowableProcessTest` surface

Both parts of this design add an attribute to the same annotation. Shown together so the eventual
implementation isn't split across two unrelated-looking diffs to the same file:

```java
// flowable-test-core
public enum FlowableTestIsolation { SHARED, SEPARATE_CONTEXT }

public @interface FlowableProcessTest {
  ...
  FlowableTestIsolation isolation() default FlowableTestIsolation.SHARED;   // Part A
  String[] processes() default {};                                          // Part B, layer 2
}
```

---

## Part A — context isolation (`isolation = SEPARATE_CONTEXT`)

### A.1 Problem

`ProcessEngine` (and its database) lives per `ApplicationContext`, not per test class. Spring's
`TestContext` cache reuses a context — same `ProcessEngine`, same `DataSource`, same tables — across
any test classes whose merged configuration is judged identical. This starter already had to work
around one instance of this (`MockExternalServiceContextCustomizer`, guarding against two classes
with different `@MockExternalService` overrides silently sharing a context). The general case is
unhandled: two plain `@FlowableProcessTest` classes with no class-specific customization share a
context by default, so process instances, history, and jobs (including still-pending timer jobs)
started by one class are visible to whichever class Spring runs next against that same context.

### A.2 Goals

- **Opt-in, per class**: a class that needs *true* isolation — a genuinely separate `ProcessEngine`,
  `DataSource`, and everything under it — can request it explicitly.
- No change to default behavior for classes that don't ask for it: context caching, and the sharing
  that comes with it, continues to work exactly as it does today unless a class opts in.
- No raw SQL, no dependency on Flowable internal/version-specific APIs.

### A.3 Mechanism

#### Why an annotation attribute, not a YAML property

A `ContextCustomizerFactory` is invoked while Spring's `TestContext` framework is still *building*
the `MergedContextConfiguration` — before `application.yml`/`application-test.yml` have necessarily
been loaded into that context's `Environment`. `MockExternalServiceContextCustomizerFactory` sidesteps
this entirely by reading straight off the test class's own annotation metadata via
`AnnotatedElementUtils`, which needs no environment resolution at all. Relying on a `flowable.test.*`
YAML property here would mean depending on the exact phase ordering of Spring Boot's environment
post-processing relative to context-customizer collection — not something to assert as reliable
without verifying it against this repo's exact Spring Boot version first. The annotation-attribute
approach has zero such uncertainty and already has a working precedent in this codebase, so it's the
recommended mechanism. `SHARED` (the default) means "no opinion" — exactly today's behavior, governed
entirely by Spring's own context-caching rules. `SEPARATE_CONTEXT` is the opt-in.

#### `StrictIsolationContextCustomizerFactory` / `StrictIsolationContextCustomizer`

New pair in `flowable-test-autoconfigure`, mirroring `MockExternalServiceContextCustomizerFactory`:

```
createContextCustomizer(testClass, configAttributes):
  isolation = AnnotatedElementUtils.findMergedAnnotation(testClass, FlowableProcessTest.class).isolation()
  if isolation != SEPARATE_CONTEXT -> return null   // no customizer -> no cache-key change
  return new StrictIsolationContextCustomizer(testClass.getName())
```

`StrictIsolationContextCustomizer` is a record wrapping the test class name, implementing
`ContextCustomizer` with `equals`/`hashCode` derived from that name (same technique
`MockExternalServiceContextCustomizer` already uses for its `overrides` set) — so a `SEPARATE_CONTEXT`
class always produces a cache key no other class can match, forcing Spring to build a brand-new
context for it every run, which — via the already-existing per-context embedded-postgres/H2
default — means a brand-new `ProcessEngine` and a brand-new database. `customizeContext(...)` itself
is a no-op; the class exists purely for its identity to poison the cache key, exactly the same trick
`@DirtiesContext` uses internally, but scoped to *this* class only rather than also forcing a rebuild
for whatever runs after it.

Because this mechanism forces a genuinely new context — new `ProcessEngine`, new database, built from
scratch the normal way an engine always starts — there is nothing left over to clean up or verify: no
leftover process instances, history, jobs, or event subscriptions of any kind, on any supported
Flowable version, since none of that state exists yet in a database nobody has used. Correctness
doesn't depend on enumerating Flowable's stateful entity types at all, which sidesteps every
version-compatibility question an explicit-cleanup alternative would have run into (an earlier,
rejected iteration of this design hit exactly that problem — see the "known gap" history preserved in
git history of the original documents this file replaces).

### A.4 Cost and trade-off

A `SEPARATE_CONTEXT` class pays for a full context rebuild: new `ProcessEngine`, new embedded
database (new H2 instance, or a new native `embedded-postgres` process/logical database depending on
provider), full schema creation, full classpath BPMN redeployment. That's the same cost Spring's
`@DirtiesContext` already imposes elsewhere — expected and accepted for a class that explicitly opts
in, since it's asking for a real guarantee, not a cheap approximation.

There is no default-on isolation in this design — a class that doesn't opt in shares context/DB with
other classes exactly as today, including any leftover process instances, history, or jobs a
previous class using that same cached context left behind. Test authors relying on `SHARED` (the
default) still need to scope assertions/queries by `processInstanceId`/`businessKey` rather than
unfiltered `list()`/`count()` calls.

---

## Part B — process-deployment allow-list (default → annotation → programmatic)

### B.1 Problem, framed the same way as the HTTP-mock precedent

Today, "which BPMN processes get deployed" is not something this starter controls or asserts at
all — it's entirely Flowable's own Spring Boot auto-deployment (`ProcessEngineAutoConfiguration`,
governed by `flowable.check-process-definitions` (default `true`),
`flowable.process-definition-location-prefix` (default `classpath*:/processes/`), and
`flowable.process-definition-location-suffixes` (default `**.bpmn20.xml`, `**.bpmn`)): every file
matching that glob gets deployed as one deployment, for every context, unconditionally. There is no
declared, single-place answer to "what processes does this test application depend on by default" —
same shape of problem `http-mock-explicit-service-registry-design.md` solved for WireMock services,
just for BPMN deployment instead of HTTP stub servers.

Three layers, in this order: **default → annotation → programmatic**, mirroring the allow-list's
"declared list of names" shape, plus two additive escape hatches.

### B.2 Layer 1 — default allow-list, `flowable.test.processes.deploy`

Same shape as `flowable.test.http-mocks.services`: optional, opt-in, absent = unchanged behavior.

```yaml
flowable:
  test:
    processes:
      root: classpath:processes        # NEW, optional — default matches Flowable's own scan prefix
      deploy:                          # NEW, optional
        - order-processing
        - carrier-dispatch
```

| `deploy` | Behavior |
|---|---|
| **absent (default)** | Unchanged — Flowable's own `checkProcessDefinitions` scan deploys every file under `processDefinitionLocationPrefix`/`-Suffixes`, exactly as today. Zero-config path stays zero-config. |
| **present** | Becomes the authoritative default deployment set. Exactly these names are deployed, resolved via `root/<name>.bpmn20.xml` (mirrors the one-file-per-process convention already used by every process in `flowable-test-example`). Any declared name whose file doesn't exist fails fast. Flowable's own classpath scan is disabled for this context (see below) so there's exactly one mechanism deciding the default set, not two racing ones. |

#### Why this must disable Flowable's own auto-deployment, not just add to it

Confirmed by decompiling `FlowableProperties` (both 7.0.0 and 7.1.0 — identical in both):
`checkProcessDefinitions` is a plain boolean toggle for Flowable's own classpath-scan deployment
step. If `deploy` is declared but Flowable's scan is left on, every file under `processes/` still
gets deployed regardless of the allow-list — the allow-list would be decorative, not authoritative,
exactly the "implicit coincidence" failure mode the HTTP-mock design was written to eliminate. So
when `deploy` is present, this starter's `EnvironmentPostProcessor` also injects
`flowable.check-process-definitions=false` (via `addFirst`, so it wins even if the consumer's own
`application.yml` set it explicitly) — the starter fully takes over the "default" deployment
decision, the same way declaring `http-mocks.services` fully takes over which WireMock servers start.

#### Validation and deployment mechanics

- **Validation** (fail fast, before context refresh): new
  `FlowableTestProcessDeploymentEnvironmentPostProcessor`, same idiom as
  `FlowableTestHttpStubEnvironmentPostProcessor` — for each declared name, checks
  `root/<name>.bpmn20.xml` resolves via `PathMatchingResourcePatternResolver`; throws
  `IllegalStateException` naming the missing process and its expected classpath location if not.
  Stashes the resolved `name -> classpathLocation` map as an environment property (same
  `HttpMockServiceRegistry`-style encode/resolve idiom), for the deployment bean below to read once
  services exist.
- **Deployment** (needs `RepositoryService`, so must happen after context refresh, not in the
  `EnvironmentPostProcessor`): new `@Bean` in `flowable-test-autoconfigure`, implementing
  `SmartInitializingSingleton` — runs automatically once all singletons (including
  `RepositoryService`) exist, with no test-framework dependency at all. This mirrors how Flowable's
  *own* auto-deployment also runs as part of context refresh, not as a JUnit hook — Layer 1 is a
  drop-in replacement for that mechanism, so it should behave like it. Deploys all resolved names as
  one deployment (`repositoryService.createDeployment().addClasspathResource(...)` per name,
  `.enableDuplicateFiltering()`, `.deploy()` — confirmed present identically on `DeploymentBuilder` in
  both 7.0.0 and 7.1.0), named after `flowable.test.processes.deploy` for traceability.

### B.3 Layer 2 — annotation, `@FlowableProcessTest(processes = {...})`

Additive to Layer 1, not a replacement — a class using this attribute still gets everything the
default layer deploys, plus these. Names resolve via the same `root/<name>.bpmn20.xml` convention (no
second dialect to learn).

Implementation: new `TestExecutionListener`, `beforeTestClass`, same registration idiom as
`FlowableKafkaConsumerLifecycleTestExecutionListener` (`spring.factories`, unconditional
registration, internal guard on `RepositoryService` bean presence). Reads
`AnnotatedElementUtils.findMergedAnnotation(testClass, FlowableProcessTest.class).processes()`,
resolves each name via `root/<name>.bpmn20.xml`, deploys with `.enableDuplicateFiltering()`.

This has to be a `TestExecutionListener`, not a `ContextCustomizerFactory`: the same reasoning
`MockExternalServiceContextCustomizer`'s Javadoc already establishes applies here —
`ContextCustomizer.customizeContext` runs before `refresh()`, when `RepositoryService` doesn't exist
yet, and even if it did, `@FlowableProcessTest.processes()` isn't one of the annotation attributes
Spring's `MergedContextConfiguration` considers for cache-key purposes, so it has no bearing on
whether two classes share a context — it only needs *bean* access at the right lifecycle point, not
a cache-key contribution. (Contrast with Part A's `isolation` attribute, which is read by a
`ContextCustomizerFactory` precisely *because* it needs to affect the cache key — the two attributes
on the same annotation are consumed by two different mechanisms for that reason.)

#### Why `.enableDuplicateFiltering()` is load-bearing here, not optional

`beforeTestClass` fires **every time**, including every time Spring reuses a cached context across
multiple classes carrying the same `processes()` value. Without duplicate filtering, each reuse would
call `deploy()` again for byte-identical content, and Flowable does not dedupe by default — each call
creates a brand-new deployment and a new process-definition version, so a suite with many classes
sharing one cached context would accumulate one extra version per class for no reason. Confirmed
present identically on `DeploymentBuilder` in both 7.0.0 and 7.1.0, so this is safe at the supported
floor. This is the same content-hash-based idempotency this repo's own `CLAUDE.md` already documents
for Flowable's classpath auto-deployment — Layer 2 just needs to opt into it explicitly since it's a
manual `deploy()` call, not Flowable's own auto-deployer (which already enables it internally).

### B.4 Layer 3 — programmatic, `ProcessTestHarness.deployProcess(...)`

```java
public void deployProcess(String processName) {
  repositoryService
      .createDeployment()
      .addClasspathResource(root + "/" + processName + ".bpmn20.xml")
      .enableDuplicateFiltering()
      .deploy();
}
```

Requires adding `RepositoryService` to `ProcessTestHarness`'s constructor — it currently holds
`RuntimeService`/`TaskService`/`HistoryService`/`ManagementService` only (`ProcessTestHarness.java:58-61`).
`FlowableTestAssertionsAutoConfiguration.processTestHarness(...)` (the sole place constructing it,
`FlowableTestAssertionsAutoConfiguration.java:40-52`) needs the extra parameter — `RepositoryService`
is already a bean Flowable's own `ProcessEngineServicesAutoConfiguration` provides, so no new
`@ConditionalOnBean` gating is needed, same as the four services already injected there.

Called explicitly, inside a test method body, whenever that test needs a process the first two
layers didn't already provide — e.g. a one-off process only one test in the whole suite exercises,
not worth declaring at class or application level.

### B.5 Why the three-layer ordering needs no explicit coordination

It falls entirely out of existing Spring/JUnit lifecycle guarantees, not out of anything this design
has to build:

```
context refresh:
  ... all singleton beans created, including RepositoryService ...
  SmartInitializingSingleton.afterSingletonsInstantiated()   <- Layer 1 (default) runs here
context handed to TestContext framework:
  TestExecutionListener.beforeTestClass(...)                  <- Layer 2 (annotation) runs here
  @Test method bodies run:
    harness.deployProcess(...)                                 <- Layer 3 (programmatic) runs here,
                                                                   whenever a test calls it
```

Layer 1 is guaranteed complete before Layer 2 starts (context refresh fully finishes before the
`TestContext` framework does anything with the resulting context). Layer 2 is guaranteed complete
before Layer 3 starts (JUnit never runs a `@Test` method before all `beforeTestClass` listeners have
returned). No explicit `@Order` annotation or coordination code is needed between the three — the
requested ordering is a direct consequence of where each layer is implemented, not a property that
has to be separately enforced.

---

## Part C — how A and B interact

Both parts add machinery that runs at different, well-defined points in the same context lifecycle.
Laying the full sequence out together, for a class using both `isolation = SEPARATE_CONTEXT` and
`processes = {...}` at once:

```
1. ContextCustomizerFactory evaluation (Part A) — isolation attribute read, cache key poisoned
   if SEPARATE_CONTEXT, decided before Spring knows whether it'll reuse or build a context.
2. Spring resolves cache key -> either reuses an existing context, or (always, for SEPARATE_CONTEXT
   classes, since their key can never match) builds a brand-new one.
3. If a new context is built: refresh() -> SmartInitializingSingleton (Part B, layer 1 default
   deploy) fires as part of that refresh.
4. TestContext framework calls beforeTestClass -> TestExecutionListener (Part B, layer 2 annotation
   deploy) fires — unconditionally, every class, whether or not step 2 built a new context.
5. @Test method bodies run -> layer 3 programmatic deploys, as needed.
```

Two composition facts worth stating explicitly rather than leaving implicit:

- **A `SEPARATE_CONTEXT` class always gets a full Layer-1 + Layer-2 deployment cycle**, never a
  cache hit that skips it — steps 3 and 4 are guaranteed to run in full because step 2 can never
  reuse an existing context for that class. This is exactly the "built from scratch, normal way an
  engine always starts" property Part A relies on for its own correctness (A.3), and it composes
  cleanly with Part B without either side needing to know about the other.
- **A `SHARED` class still runs step 4 every single time**, even on a cache hit — `beforeTestClass`
  is a per-class JUnit guarantee, independent of whether the underlying context is fresh or reused.
  This is precisely why B.3's `.enableDuplicateFiltering()` is load-bearing rather than a nice-to-have:
  without it, every `SHARED` class sharing a cached context with an annotation-declared `processes`
  set would otherwise create a redundant deployment on every run.

No coordination code is needed between the two parts beyond what's already specified in A.3 and
B.2–B.5 individually — the composition above is a consequence of implementing each part correctly on
its own, not an additional mechanism to build.

## Part D — combined `spring.factories` additions

```
org.springframework.boot.env.EnvironmentPostProcessor=\
  ...(existing)...,\
  com.flowabletest.autoconfigure.deployment.FlowableTestProcessDeploymentEnvironmentPostProcessor

org.springframework.test.context.ContextCustomizerFactory=\
  ...(existing)...,\
  com.flowabletest.autoconfigure.isolation.StrictIsolationContextCustomizerFactory

org.springframework.test.context.TestExecutionListener=\
  ...(existing)...,\
  com.flowabletest.autoconfigure.deployment.FlowableProcessAnnotationDeploymentTestExecutionListener
```

Plus one new `@Bean`/`SmartInitializingSingleton` in `flowable-test-autoconfigure` (Part B, layer 1 —
not a `spring.factories` entry, an ordinary autoconfiguration bean).

## Part E — interaction with existing mechanisms (combined)

- **`embedded-postgres` `shared` instance-scope**: a `SEPARATE_CONTEXT` class still gets its own
  fresh *logical* database under the shared server, exactly as any other new context would — falls
  out of `EmbeddedPostgresInstanceScopeCondition.Shared` provisioning "a fresh logical database per
  context" with no special-casing needed.
- **`MockExternalServiceContextCustomizer`**: all three customizer/listener mechanisms across both
  parts coexist independently on `spring.factories` — a class can combine `@MockExternalService`
  overrides, `isolation = SEPARATE_CONTEXT`, and `processes = {...}` freely; their contributions
  (cache-key or deployment) simply combine.
- **Shared Kafka broker / `FlowableKafkaConsumerLifecycleTestExecutionListener`**: unaffected by
  either part — that listener already handles "new context, same shared broker" for any cause of a
  context miss, and has no dependency on process-engine deployment state.
- **`FlowableCompatibilityGuardAutoConfiguration`**: unaffected — only checks `ProcessEngine.VERSION`
  against the supported range, independent of both isolation and deployment concerns.
- **`EventRegistryChannelScanner`**: unaffected, but worth noting the parallel — that scanner already
  discovers Kafka topics from whatever `.channel` files exist, with no allow-list of its own. A
  process deployed via any Part B layer still participates in Kafka correlation exactly as today if
  its BPMN references a channel.

## Part F — non-goals

- Part A does not solve pollution between test *methods* within the same class — out of scope.
- Part B does not change the one-file-per-process convention (`root/<name>.bpmn20.xml`) — no
  folder-of-multiple-files convention like WireMock's `mappings/*.json`, since a BPMN process is
  naturally one self-contained file.
- Part B does not restrict or validate layer 2/3 names against the layer 1 allow-list — same
  "escape hatch, not a default" relationship `@MockExternalService` already has with
  `http-mocks.services`.
- Neither part is a breaking change: both new annotation attributes default to today's behavior
  (`SHARED`, empty `processes[]`), and `flowable.test.processes.deploy` absent means Flowable's own
  classpath auto-deployment behaves exactly as it does today.
- This document is a design only — no code has been written. Implementation would add: two new
  enums/attributes on `@FlowableProcessTest` (`flowable-test-core`), two new properties and their
  `EnvironmentPostProcessor`, one `SmartInitializingSingleton` bean, one new `ContextCustomizerFactory`/
  `ContextCustomizer` pair, one new `TestExecutionListener`, one new `ProcessTestHarness` constructor
  parameter and method, and `spring.factories`/autoconfig wiring updates (all in
  `flowable-test-autoconfigure`) — plus tests proving each part individually and the Part C
  composition against the real pinned engine, per this repo's existing internal-validation-suite
  convention.
