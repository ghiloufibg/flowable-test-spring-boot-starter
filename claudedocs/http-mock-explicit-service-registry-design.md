# Design: Explicit HTTP-mock service registry (replacing filesystem-driven "which server is used")

Date: 2026-07-14 (implemented 2026-07-14)
Status: Implemented
Relates to: `HttpMockDiscovery`, `FlowableTestHttpStubEnvironmentPostProcessor`,
`EmbeddedFlowableHttpMockSupport`, `flowable-test-starter-design.md` section 4.3

## Problem

Question that triggered this design: **when a test class carries no `@MockExternalService`
annotation, which WireMock server does it actually talk to?**

Today's answer is fully deterministic but nowhere written down as a single fact a reader can point
to — it falls out of two independent, uncoordinated mechanisms:

1. `FlowableTestHttpStubEnvironmentPostProcessor` scans `flowable.test.http-mocks.root`
   (`classpath:httpmocks` by default) and starts **one WireMock server for every immediate
   subfolder it happens to find on the classpath** (`HttpMockDiscovery.discoverDefaultServices`).
   The *set of servers that exist* is whatever folders are present — nothing declares this list
   ahead of time.
2. Whether a given one of those servers is actually *used* by the code under test is decided
   entirely by production code elsewhere — e.g. `FraudCheckDelegate`'s
   `@Value("${fraud-check-service.base-url}")` — which only resolves if a folder happens to be
   named `fraud-check-service`, matching by convention, not by any config this project owns or
   validates.

So "which server backs an unannotated test" is really "whichever folder name happens to coincide
with whatever property key some unrelated piece of production code reads" — correct today by
coincidence of naming discipline, but not asserted or fail-fast-checked anywhere:

- A stray/leftover/copy-pasted folder under `httpmocks/` silently starts its own WireMock server
  that nothing ever calls — harmless but invisible, no signal anything is wrong.
- A typo in either the folder name or the `@Value` key produces no error pointing at the mismatch —
  Spring just fails later with a generic "could not resolve placeholder
  '${fraud-check-service.base-url}'", with nothing connecting that failure back to "check your
  `httpmocks/` folder name."
- There is no single place in a consumer's `application.yml` that says "this application/test
  depends on these external services" — that fact only exists implicitly, split across whatever
  folders happen to exist under `src/test/resources/httpmocks/` and whatever `@Value`/
  `@ConfigurationProperties` prefixes happen to appear in production code.

This is the "guessing" in scope for this design: not randomness, but **undeclared, implicit
coincidence** standing in for something that should be one explicit, validated fact in
`application.yml`.

## Recommended approach: optional explicit allow-list, `flowable.test.http-mocks.services` (opt-in — absent means unchanged behavior)

Same shape as `flowable.test.kafka.broker-scope` and
`flowable.test.datasource.embedded-postgres.instance-scope`: a new, standalone, opt-in property
that turns an implicit behavior into a declared one, without changing the zero-config default path
CLAUDE.md commits this project to.

```yaml
flowable:
  test:
    http-mocks:
      root: classpath:httpmocks     # unchanged
      services:                     # NEW, optional
        - fraud-check-service
        - payment-gateway
```

| `services` | Behavior |
|---|---|
| **absent (default)** | Unchanged — every immediate subfolder under `root` is discovered and started, exactly as today. Zero-config path stays zero-config. |
| **present** | Becomes the authoritative list of services this application's tests depend on. Exactly these names are started, resolved via the existing `root/<name>` convention (no change to where mapping files live). Any declared name whose `root/<name>/mappings` folder does not exist fails fast, at environment-post-processing time — before context refresh, with a message naming the missing service and its expected classpath location. Any folder present under `root` but **not** in the declared list is left alone (not started) — makes stray/leftover folders inert instead of silently spinning up an unused server. |

This directly answers the triggering question: once `services` is declared, "which server does an
unannotated test use" has exactly one answer, readable in one place — the same list, every time,
independent of what happens to exist on the classpath.

### Fallback is the actual default, not a secondary path

This is additive, not a replacement: `FlowableTestHttpStubEnvironmentPostProcessor` only changes
behavior for a consumer who has explicitly opted in by setting `services`. The resolution logic is
a single branch, checked once per context, with today's scan as the unconditional fallback branch:

```
services = environment.getProperty("flowable.test.http-mocks.services")   // List<String>, may be absent/empty

if services is absent or empty:
    # IDENTICAL to today, unchanged code path: HttpMockDiscovery.discoverDefaultServices(root)
    # scans every immediate subfolder of `root` and starts a server for each one found.
    servicesToStart = discoverDefaultServices(root)
else:
    # NEW path, only reached when the consumer declared the property.
    servicesToStart = services, each resolved via the existing root/<name> convention,
                      fail-fast if root/<name>/mappings is missing for any declared name
```

A consumer who never sets `flowable.test.http-mocks.services` — including every test in this repo
today — runs the exact same `discoverDefaultServices` call, with the exact same scan-every-subfolder
semantics, that runs today. Nothing about `HttpMockDiscovery` itself changes; the new property only
gates whether its result is used as-is (fallback) or replaced by the declared list (opt-in path).

### Why an allow-list, not a name→location map

`HttpMockDiscovery` already derives the location from the name via the `root/<name>` convention,
and that convention is exactly what `@MockExternalService(stubs = ...)` exists to override on a
per-test-class basis. Duplicating `name -> classpathLocation` pairs in `application.yml` would
create two sources of truth for the same convention and reopen the question this design is trying
to close. A plain list of expected names keeps the convention as the single place a location is
ever computed, while still making the *set of names* explicit and validated.

### Why validation lives in the `EnvironmentPostProcessor`, not later

`EmbeddedFlowableHttpMockSupport.startNewServer` already throws `IllegalStateException` for a
missing `mappings` folder — but only reactively, the first time something calls `ensureStarted` for
that name (today: only names the filesystem scan already found, so a wholly *absent* folder for a
declared name is never even attempted and never surfaces an error). Moving validation into
`FlowableTestHttpStubEnvironmentPostProcessor`, driven by the declared list instead of the scan
result, means a missing service is caught for every declared name, at the earliest possible point
(before `ApplicationContext` refresh even begins), with one consistent error path instead of
depending on which names the classpath scan happened to turn up.

### Interaction with `@MockExternalService`

Unchanged and unrestricted. A test-class-level override is a deliberate escape hatch for that one
class, not a "default" — it is not required to appear in `services`, and declaring `services` does
not stop `@MockExternalService` from pointing at a name outside that list for a single test class.
The two mechanisms answer different questions: `services` says "what does this application depend
on by default," `@MockExternalService` says "this one test class needs something different."

## Case study: `flowable-test-example`'s two `fraud-check-service*` folders

Concrete instance of exactly this problem, already present in this repo:

- `httpmocks/fraud-check-service/mappings/approve.json` — `POST /v1/fraud-check` → `approved: true`
- `httpmocks/fraud-check-service-manual-review/mappings/decline.json` — the **identical**
  `POST /v1/fraud-check` request → `approved: false`

Two folders stubbing the same endpoint isn't a conflict — two different test scenarios need two
different responses from the same production endpoint. Tracing how it actually resolves today:

- `OrderAutoApprovedFlowTest` carries no `@MockExternalService`. It gets the plain auto-discovered
  default: the folder literally named `fraud-check-service` — the same name
  `FraudCheckDelegate`'s `@Value("${fraud-check-service.base-url}")` reads — resolves to
  `approve.json`.
- `OrderManualReviewFlowTest` and `PaymentCallbackEventRegistryTest` both carry
  `@MockExternalService(name = "fraud-check-service", stubs =
  "classpath:httpmocks/fraud-check-service-manual-review")`. `MockExternalServiceContextCustomizer`
  adds its property source with `addFirst`, so for those two contexts specifically, that same
  property key (`fraud-check-service.base-url`) instead resolves to a *different* WireMock server
  backed by `decline.json`.

**So which one "wins" is not actually ambiguous, per context** — it's decided once, entirely by
whether that test class carries `@MockExternalService`, resolved before any bean is created, and a
given context's property value is never simultaneously both. The real gap this case study exposes
is narrower and matches the problem statement exactly:
`FlowableTestHttpStubEnvironmentPostProcessor` scans and auto-starts a WireMock server for
`fraud-check-service-manual-review` too, for **every** context — including
`OrderAutoApprovedFlowTest`, which never references it — because nothing distinguishes "a real
default service" from "a folder that only ever exists as an `@MockExternalService(stubs=...)`
override target." The result: an extra, silently-started, never-queried WireMock server per test
context, and a folder listing under `httpmocks/` that gives no visual signal which entries are
defaults versus override-only variants — a reader has to grep every test class's annotations to
find out.

**Why `fraud-check-service`, never `fraud-check-service-manual-review`, absent an annotation — the
exact mechanism, not just the outcome:** `HttpMockDiscovery` names each Environment property after
the *folder name*, not the endpoint path any server stubs — `fraud-check-service.base-url` and
`fraud-check-service-manual-review.base-url` are two unrelated keys in the property map, pointing at
two different ports. `FraudCheckDelegate`'s `@Value("${fraud-check-service.base-url}")` is a fixed
string literal in Java source; Spring resolves it via plain exact-string key lookup
(`environment.getProperty("fraud-check-service.base-url")`) — no fuzzy or prefix matching, and
critically, no knowledge of which URL path either server stubs. So the two servers stubbing the
identical `POST /v1/fraud-check` path is irrelevant to selection: that only matters *after* a
request already reaches one specific port, and which port that is was already settled at
bean-construction time by which literal key appears in `@Value`. `fraud-check-service-manual-review`
isn't "outvoted" — it's simply never looked up by anything, unless `@MockExternalService` rebinds
the `fraud-check-service.base-url` key itself to point at it for that context.

**How the `services` allow-list fixes this exact case:**

```yaml
flowable:
  test:
    http-mocks:
      services:
        - fraud-check-service   # the only real default — NOT fraud-check-service-manual-review
```

Declaring this excludes `fraud-check-service-manual-review` from the environment
post-processor's default auto-start entirely; it is started only on demand, the same way it already
is today, by a test class's own `@MockExternalService(stubs = ...)` (unchanged mechanism). The
folder listing under `httpmocks/` then has one authoritative answer for "which of these are
defaults" — `application.yml`'s `services` list — instead of an implicit rule a reader has to
reverse-engineer from test source.

## Non-goals

- Does not change how a service's mapping/`__files` folder is located (`root/<name>` convention,
  or `@MockExternalService(stubs = ...)` for overrides) — only whether the *name* is asserted
  in config versus inferred from whatever the classpath scan turns up.
- Does not introduce per-service fixed ports or any other WireMock server-configuration knob —
  orthogonal, not requested here.
- Does not restrict or validate `@MockExternalService` overrides against the declared list.
- Not a breaking change: default behavior (property absent) is bit-for-bit identical to today —
  see "Fallback is the actual default, not a secondary path" above.

## Documentation updates — done

- README's "Declarative HTTP mocking" section: documents `flowable.test.http-mocks.services`
  alongside the existing `root`/`enabled` properties, with the same "absent = current behavior"
  framing used for `broker-scope`/`instance-scope`.
- `flowable-test-example/src/main/resources/application.yml`: declares
  `flowable.test.http-mocks.services: [fraud-check-service]` (nested under the file's existing
  top-level `flowable:` key, not a second one — YAML disallows duplicate top-level keys) as the
  worked example proving the property in the one place this repo already exercises HTTP mocking
  end-to-end.

## Test plan — implemented

- `HttpMockDiscovery.resolveDeclaredServices` resolves the `root/<name>` convention for each
  declared name and throws `IllegalStateException` (naming the missing service and expected
  classpath location) if its `mappings` folder is absent.
- `FlowableTestHttpStubEnvironmentPostProcessor` binds `flowable.test.http-mocks.services` via
  `Binder`/`Bindable.listOf(String.class)`; empty/absent falls back to the unchanged
  `discoverDefaultServices` scan, non-empty routes through `resolveDeclaredServices` instead.
- `FlowableTestHttpStubServicesAllowListTest` (new, drives `SpringApplicationBuilder` directly --
  same rationale as `FlowableKafkaBrokerScopePerContextTest`, since `EnvironmentPostProcessor`
  isn't triggered by `ApplicationContextRunner`), against a dedicated `httpmocks-services-test`
  root with `declared-service`/`undeclared-service` subfolders so the assertions can't be
  influenced by any other test's use of the shared default `httpmocks/` root:
  - `services` absent: both subfolders start, `<name>.base-url` injected for both -- proves the
    fallback is bit-for-bit the same scan as before.
  - `services=[declared-service]`: only `declared-service.base-url` is injected;
    `undeclared-service.base-url` is absent.
  - `services=[declared-service, missing-service]`: context startup throws
    `IllegalStateException` mentioning `missing-service` and its expected
    `httpmocks-services-test/missing-service/mappings` path, before any bean is created.
- Full existing `flowable-test-autoconfigure` suite (all classes, including
  `FlowableTestHttpStubAutoConfigurationTest` and `MockExternalServiceOverrideTest`) and the full
  `flowable-test-example` suite (`OrderAutoApprovedFlowTest`, `OrderManualReviewFlowTest`,
  `PaymentCallbackEventRegistryTest`, now running with `services: [fraud-check-service]`
  declared) pass unmodified -- proving both the default path and the
  `@MockExternalService`-outside-the-declared-list interaction are untouched. Full reactor
  `mvn install` passes.
