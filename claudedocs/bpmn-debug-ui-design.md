# Design: Minimalist BPMN debug UI (`flowable-test-debug-ui`)

Date: 2026-07-15 (design only — not yet implemented)
Status: Proposed — core API mechanism verified against real `flowable-engine`/`flowable-image-generator`
7.1.0 jars; see "Risks and open questions" for what is not yet resolved
Relates to: `ProcessDiagnosticsCollector`, `ProcessInstanceTracker`, `ProcessTestHarness`,
`FlowableTestDiagnosticsAutoConfiguration`

## Problem

Flowable's own Modeler/Task/Admin/IDM UI (Apache 2.0) let a developer see a running process
instance's BPMN diagram with the current activity highlighted, its variables, its pending tasks,
and its activity history — but that app was pulled from mainline Flowable releases ("recent
releases focus on the main Flowable Engines and REST APIs... there are no UI applications"), and
even at the last release that had it (`flowable-6.8.0`), it was a full Angular SPA + Spring MVC
REST backend bundling deployment management, IDM, and modeler editing — none of which this
starter needs.

Goal: give a developer using this starter the same *inspect a running process instance* experience
— diagram, variables, tasks, history — as a small, opt-in, local-only capability, without porting
that legacy app or its unrelated surface area.

**Non-goals**: not a replacement for Flowable's production Admin UI; no deployment management, no
IDM, no BPMN modeling/editing; no mutating the process (no task completion, no signal-triggering
from the browser) — this stays a read-only debugging aid, not a second control-plane API; never
active in CI.

## Rejected alternatives

**Deploy on the consumer's own embedded Tomcat.** `@FlowableProcessTest` defaults
`webEnvironment` to `MOCK` — no real servlet container exists in most test contexts, and many
Flowable BPMN projects have no `spring-boot-starter-web` on the classpath at all (a worker/batch
service has no reason to). Requiring a real embedded container would turn an opt-in debugging aid
into a mandatory dependency on the consumer's stack shape. Reusing the *same* container (when one
does exist) would also register our servlets/routes into the exact same `ServletContext` — and
therefore the same security filter chain, CORS config, and routing table — as the system under
test. That's the one thing this starter has consistently avoided (`EnvironmentPostProcessor`s and
dedicated embedded brokers/servers for Kafka and WireMock instead of registering into the
consumer's own bean graph); a debug UI is not exempt from that principle.

**Deploy Flowable's own `flowable-rest`/`flowable-spring-boot-starter-rest-api`.** Confirmed via its
own docs: it requires HTTP Basic Auth by default, backed by a Spring Security
`UserDetailsService` wired to `IdmIdentityService`, needing an admin user provisioned via
`flowable.rest.app.admin.*`. It also exposes Flowable's *entire* production REST surface
(deployments, forms, DMN, content, IDM, batches), not just process-instance inspection, and its own
Spring Security config would land in the same `ApplicationContext` as the system under test — the
same context-pollution problem as above, plus auth friction for a "just let me look at this"
tool. It also doesn't render anything by itself (REST only) — getting the actual diagram view back
would still require standing up the legacy Angular frontend, which doesn't avoid the "port an
unmaintained legacy SPA" problem, it just relocates it one hop away.

**Recommended: a dedicated, isolated HTTP listener, embedded directly in this starter**, reusing
only the *data* Flowable/this starter already compute (`ProcessDiagnosticsCollector`,
`ProcessInstanceTracker`) and one still-current, still-shipped Flowable API
(`ProcessDiagramGenerator`) for the visual.

## How the legacy UI actually built this (grounds this design in a working precedent)

Pulled directly from `flowable/flowable-engine` at tag `flowable-6.8.0`, not reasoned from
scratch:

`flowable-rest`'s `ProcessInstanceDiagramResource` renders the highlighted diagram in three calls:

```java
List<String> activeActivityIds = runtimeService.getActiveActivityIds(processInstance.getId());
BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinition.getId());
ProcessDiagramGenerator generator = processEngineConfiguration.getProcessDiagramGenerator();
InputStream png = generator.generateDiagram(bpmnModel, "png", activeActivityIds, List.of(), false);
```

Verified directly against this repo's actual `flowable-engine-7.1.0.jar`/`flowable-image-generator-7.1.0.jar`
via `javap` (not assumed from the legacy source summary): `RuntimeService.getActiveActivityIds(String)`,
`RepositoryService.getBpmnModel(String)`, and `ProcessEngineConfiguration.getProcessDiagramGenerator()`
all exist with these exact signatures. `ProcessDiagramGenerator.generateDiagram` has no 4-arg
`(BpmnModel, String, List, List)` overload, though — the shortest matching one takes a trailing
`boolean` (`drawSequenceFlowNameWithNoLabelDI`), as corrected above.

`flowable-image-generator` (the module `ProcessDiagramGenerator` lives in) is confirmed present in
this repo's local `.m2` for both `7.0.0` and `7.1.0` — i.e. across this starter's entire supported
Flowable range — so this mechanism is available unchanged.

`flowable-ui-admin-logic`'s `ProcessInstanceService` fetches variables/tasks/history over HTTP
(`history/historic-variable-instances`, `runtime/executions/{id}/activities`,
`history/historic-activity-instances`, `history/historic-task-instances`) because the legacy Admin
app is a *separate remote process* talking to a Flowable REST server. We don't need that split:
this debug UI runs inside the same JVM as the test's engine, so it calls `HistoryService`/
`RuntimeService`/`TaskService` directly — and `ProcessDiagnosticsCollector` already does exactly
that (it was built for the on-failure-diagnostics capability). The debug UI is a thin HTTP facade
over data this starter already produces, not new domain logic.

## Connection architecture: bean injection, not a network/JDBC connection

```
Same ApplicationContext (one per Spring test context — SHARED or SEPARATE_CONTEXT)
┌──────────────────────────────────────────────────────────────┐
│  Flowable's own beans          This starter's beans           │
│  ProcessEngine                 FlowableTestDebugUiAutoConfig:  │
│  RuntimeService     ◄────────  - DebugUiServer                │
│  HistoryService     ◄────────  - ProcessInstanceDiagramRenderer│
│  TaskService        ◄────────  - HTTP handlers                │
│  RepositoryService  ◄────────                                 │
│  DataSource (H2/PG)                                           │
│         ▲                              ▲                      │
│    JUnit test thread            HttpServer handler thread(s)  │
└──────────────────────────────────────────────────────────────┘
```

- **No new connection, no new engine, no direct `DataSource` access.** Every read goes through the
  same `RuntimeService`/`HistoryService`/`TaskService`/`RepositoryService` beans the test class
  itself uses — whichever `DataSource` is active (H2, embedded-postgres `per-context`, or
  `shared`-instance-with-per-context-logical-database) is invisible to this module, exactly like
  `ProcessDiagnosticsCollector` today.
- **One server per Spring context, not one per JVM.** `@Bean`s are per-`ApplicationContext`, and
  Spring's test-context cache can hold several contexts alive at once (default cache size 32), so a
  `FlowableTestIsolation.SEPARATE_CONTEXT` class gets its own `DebugUiServer` bound to its own
  engine/DB. Multiple debug-UI instances can be alive simultaneously on different ports.
- **Ephemeral port + context-id log line disambiguates them.** `flowable.test.debug-ui.port`
  defaults to `0` (OS-assigned), and the startup log includes `ApplicationContext#getId()` (Spring
  assigns this automatically): `Flowable Test debug UI at http://localhost:53421 (context a1b2c3)`.
- **Cross-thread access is already safe.** `RuntimeService` etc. are already called from the async
  job executor and Kafka consumer threads elsewhere in this codebase; the HTTP handler threads are
  no different.
- **Consistency is eventual, not push-based.** A handler thread's read can race the test thread
  mid-transaction; the page's `<meta http-equiv="refresh">` is the only "live update" mechanism —
  an accepted trade-off for staying minimal (no WebSocket/SSE).
- **Known scoping limitation**: `ProcessInstanceTracker` resets on every test method's
  `@BeforeEach`, so the instance list only ever reflects the *currently executing test method*, even
  though the server/DB live for the whole cached context's lifetime.

## Module & dependencies

New Maven module `flowable-test-debug-ui`, sibling to `flowable-test-core`/
`flowable-test-autoconfigure`, added to the root reactor but **not** pulled in by
`flowable-test-starter`'s aggregator — a consumer adds it as an explicit extra test-scope
dependency, which is itself the first opt-in gate.

```xml
<dependency>
    <groupId>com.flowabletest</groupId>
    <artifactId>flowable-test-debug-ui</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Dependencies: `flowable-test-core` (compile — needs `ProcessDiagnosticsCollector`/
`ProcessInstanceTracker`/`ProcessDiagnosticsReport` types), `flowable-test-autoconfigure` (compile —
`afterName`-orders itself after `FlowableTestDiagnosticsAutoConfiguration`), `flowable-engine` +
`flowable-image-generator` (both `provided`, same pattern as every other module — no Flowable
version bundled, consumer's own `flowable-spring-boot-starter` is the sole runtime source, same
invariant as the rest of this project). No servlet-container/Spring MVC dependency — routes are
plain `com.sun.net.httpserver.HttpHandler`s (JDK-provided, zero new runtime dependency).

**`flowable-image-generator` must be pinned to `${flowable.version}` in the root `pom.xml`'s
`dependencyManagement`, not a hardcoded version** — the same property the existing
`-Dflowable.version=7.0.0|7.1.0` CI matrix override already applies to `flowable-engine`,
`flowable-event-registry-spring`, and `flowable-spring-boot-starter`. Pinning it independently would
silently decouple this module's diagram-rendering code from the compatibility guarantee the rest of
the project maintains. Verified via `javap` against the real `flowable-engine`/
`flowable-image-generator` jars at both `7.0.0` and `7.1.0` (the two versions this repo's CI matrix
actually builds against): `RuntimeService.getActiveActivityIds`, `RepositoryService.getBpmnModel`,
`ProcessEngineConfiguration.getProcessDiagramGenerator()`, and the 5-arg `generateDiagram` overload
used above are identical across both — the code compiles unchanged at either end of the supported
range. As with the rest of this project, that's evidence for the two tested endpoints specifically,
not a guarantee for every point release in between (no 8.0.0 exists to check against either, and it
is excluded from the range regardless) — the same evidentiary basis the rest of this starter's
Flowable-version-compatibility claim already rests on, no stronger and no weaker.

Package `com.flowabletest.debugui`, own
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot
merges these across jars — adding the dependency is sufficient, no consumer wiring required).

## Activation: three independent gates

1. **Dependency presence** — the module isn't even on the classpath unless explicitly added.
2. **`flowable.test.debug-ui.enabled=true`** (default `false`) — `@ConditionalOnProperty`, must be
   turned on explicitly, e.g. `mvn test -Dflowable.test.debug-ui.enabled=true -Dtest=MyTest`
   locally. Never set to `true` in a committed CI profile.
3. **A hard-coded `CI` environment-variable check inside the bean factory method itself** (not just
   the `@Conditional` annotation) — refuses to start regardless of the property, logging a warning.
   `CI` is the de facto standard every CI system sets (GitHub Actions, GitLab, CircleCI, Jenkins
   plugins, Travis). This is the gate that actually guarantees "never in CI," independent of anyone
   remembering to unset the property.

`FlowableTestDebugUiAutoConfiguration` also backs off entirely via
`@ConditionalOnBean({ProcessEngine.class, ProcessDiagnosticsCollector.class,
ProcessInstanceTracker.class})` if the diagnostics capability itself is disabled, since this module
is built entirely on that data.

## Component design

| Class | Responsibility |
|---|---|
| `FlowableTestDebugUiAutoConfiguration` | Wires everything below; holds the three-gate activation logic. |
| `DebugUiProperties` | `@ConfigurationProperties(prefix = "flowable.test.debug-ui", ignoreUnknownFields = false)` record: `enabled` (default `false`), `port` (default `0`). Same pattern as `FlowableTestDiagnosticsProperties` — free IDE autocomplete/typo fail-fast. |
| `DebugUiServer` | `SmartLifecycle`. `start()` binds `HttpServer` to `localhost` only, registers the three handlers, logs the URL + context id. `stop()` calls `httpServer.stop(0)`. |
| `ProcessInstanceDiagramRenderer` | `renderPng(String processInstanceId): byte[]` — the three-call chain from the legacy resource above. |
| `InstanceListHandler` | `GET /` — HTML list from `ProcessInstanceTracker.trackedProcessInstanceIds()`. |
| `InstanceDetailHandler` | `GET /instances/{id}` — HTML page: `<img>` tag, variables table, tasks, activity trail, all sourced from `ProcessDiagnosticsCollector.collect(id)`; `<meta http-equiv="refresh" content="3">`. |
| `DiagramImageHandler` | `GET /instances/{id}/diagram.png` — streams `ProcessInstanceDiagramRenderer` output as `image/png`. |

All HTML generated with Java 21 text blocks — no template engine, no JS framework, no build step.

## Endpoint contract

| Method | Path | Response |
|---|---|---|
| `GET` | `/` | `text/html` — links to every tracked process instance for the current test method |
| `GET` | `/instances/{id}` | `text/html` — diagram + variables + tasks + activity trail, auto-refreshing |
| `GET` | `/instances/{id}/diagram.png` | `image/png` — current-activity-highlighted diagram |

Deliberately no `POST`/mutation routes in this design.

## Properties

| Property | Default | Description |
|---|---|---|
| `flowable.test.debug-ui.enabled` | `false` | Opt-in switch; intended to be forced off under CI (see risk below — the detection isn't airtight). |
| `flowable.test.debug-ui.port` | `0` | Ephemeral (OS-assigned) by default so multiple concurrently-cached Spring contexts never collide. |

## Risks and open questions (unresolved as of this writing)

Verified against the real `flowable-engine-7.1.0.jar`/`flowable-image-generator-7.1.0.jar` via
`javap`: `RuntimeService.getActiveActivityIds`, `RepositoryService.getBpmnModel`, and
`ProcessEngineConfiguration.getProcessDiagramGenerator()` all exist as described above (the
`generateDiagram` call was wrong in an earlier draft of this doc — fixed). That verification
covers the diagram-rendering mechanism, not the design as a whole. The following are real gaps,
not yet resolved:

1. **The CI-environment check is a heuristic, not a guarantee.** `CI=true` is set by GitHub Actions
   (confirmed — this repo's own `.github/workflows/ci.yml` runs under it), GitLab, CircleCI, and
   Travis, but is not universal across every enterprise Jenkins/TeamCity/Bamboo setup. For an
   internal company framework specifically, this needs to be confirmed against whatever CI system
   is actually in use before relying on it as the safety net — either by checking multiple known CI
   env vars, or by treating "never set `enabled=true` in a committed CI profile" as the primary
   control and the env-var check as defense-in-depth only, not a guarantee.
2. **No behavior specified for three real edge cases**: a BPMN process definition with no graphical
   notation (Flowable's own legacy resource throws `FlowableIllegalArgumentException` here — this
   design doesn't say what `DiagramImageHandler` returns instead, e.g. a placeholder image vs. a
   4xx with a message), an already-ended process instance (`getActiveActivityIds` returns empty —
   should the diagram show the last completed activity, or render unhighlighted?), and a
   process-instance ID that doesn't resolve at all (no 404 behavior defined).
3. **The loopback-only bind is stated as intent, not pinned down as an implementation detail.** It
   must concretely be `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),
   port), 0)` — the easy-to-reach-for `new InetSocketAddress(port)` constructor binds all network
   interfaces, which would silently defeat the entire "never reachable off this machine" premise.
   This needs to be an explicit implementation checklist item, not left implicit.
4. **`SmartLifecycle` phase ordering relative to Flowable's own async-executor startup is reasoned
   (constructor injection implies `ProcessEngine` construction already completed), not verified.**
   Worth an explicit test once implemented: issue a request in the same instant the context
   finishes refreshing and confirm the engine is actually ready to serve it.
5. **Zero code and zero tests exist for any of this.** Everything above is a paper design grounded
   in checked facts, not a validated implementation — treat it as a starting point for the phased
   build below, not as something already proven to work end to end.

## Implementation phases

1. Module skeleton + `DebugUiProperties` + the three-gate `FlowableTestDebugUiAutoConfiguration`
   (no server logic yet — prove activation/back-off behavior with tests first, mirroring how
   `FlowableTestDiagnosticsAutoConfiguration` is tested today).
2. `ProcessInstanceDiagramRenderer` + `DiagramImageHandler` — smallest end-to-end vertical slice
   (one PNG endpoint), validated against a real deployed process instance.
3. `InstanceListHandler` + `InstanceDetailHandler`, reusing `ProcessDiagnosticsCollector` output
   directly rather than re-querying the engine.
4. README section + `flowable-test-example` opt-in demonstration (consistent with how every other
   capability in this repo is validated end-to-end against a real consumer app).
