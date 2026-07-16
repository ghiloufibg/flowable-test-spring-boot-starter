# Design: debug UI UX enhancements — benchmarked against Flowable's own UIs

Date: 2026-07-16 (design only — not yet implemented)
Status: Proposed — API surface verified against real `flowable-*-7.1.0` jars via `javap`; feature
set grounded in Flowable's own published documentation (cited inline), not assumed
Relates to: `flowable-test-debug-ui` (`Layout`, `InstanceListHandler`, `InstanceDetailHandler`),
`ProcessDiagnosticsReport`, `ProcessDiagnosticsCollector` (`flowable-test-core`)

## Problem

The debug UI (shipped in two prior iterations: initial build, then a Flowable-palette restyle) is
mechanically solid — diagram, variables, tasks, activity trail, failed jobs, tabs, filtering,
zoom, auto-refresh — but reads as a single flat inspector. The ask now is to make it feel more
*engaging*, and to ground new features in what Flowable's own UIs — both the open-source heritage
apps and the commercial **Flowable Work** / **Flowable Control** — actually consider valuable
enough to build, rather than inventing features speculatively.

**Non-goal (unchanged from the original design)**: this stays a read-only, single-test-method,
localhost-only inspector. Nothing here adds mutation (task completion, signal-triggering),
authentication, multi-tenant/user management, or turns this into a second control plane.

## Research: what Flowable's own UIs actually offer

Pulled from Flowable's published documentation, not from memory:

| Surface | What it shows | Source |
|---|---|---|
| Flowable Work — task/work-item view | Header with title, status, color-coded **overdue** indicator, assignee, **due date**; tabs: Documents, People (involved users), Conversation/Comments, Subtasks, **History** (audit events) | [Exploring Flowable Work UI and tasks](https://documentation.flowable.com/latest/howto/tutorial/task-master) |
| Flowable Work — case view | Predefined pages: Open Tasks, Work Form, Chat, Comments, People, Sub Items, Documents, **Audit Trail** | [Case Views](https://documentation.flowable.com/latest/reactmodel/cmmn/concept/case-view) |
| Flowable Work — audit trail | Scrollable list of events for a work item, ordered by creation time ascending, with human-readable message rendering | [Building an Audit Stream](https://documentation.flowable.com/latest/howto/howto/howto-audit-stream), [Audit](https://documentation.flowable.com/latest/reactmodel/bpmn/reference/audit) |
| Flowable Control — dashboards | Running/Completed instances (with **top-10-by-definition** breakdown), system resources (CPU/heap/DB connections), engine operations, async-executor throughput, job failure/dead-letter counts, active users — all **cluster/ops metrics over a selectable time range** | [Dashboards](https://documentation.flowable.com/latest/user/control/dashboards) |
| Engine core (not UI-specific, available via plain API) | `Task.getDueDate()`, `getPriority()`, `getCreateTime()`; `HistoricTaskInstance.getDurationInMillis()`, `getDeleteReason()`; `TaskService.getIdentityLinksForTask()` with `ASSIGNEE`/`CANDIDATE`/`OWNER`/`PARTICIPANT`/`STARTER` types; `RepositoryService.getResourceAsStream(deploymentId, name)` for the deployed BPMN XML; `HistoricProcessInstance.getSuperProcessInstanceId()` + `ProcessInstanceQuery.superProcessInstanceId(id)` for call-activity parent/child linkage | Verified via `javap` against `flowable-task-service-api-7.1.0.jar`, `flowable-engine-7.1.0.jar` — see "API verification" below |

### What Flowable's UI treats as legitimate is a smaller set than "everything a workflow console could show"

Even Flowable's own commercial console keeps a hard line: **Control**'s dashboards are exclusively
cluster/infrastructure metrics (CPU, JVM heap, DB connection pool, REST request counts) — nothing
about an individual process instance's business data lives there. **Work**'s per-item view is the
one that goes deep on a single item (task/case), and even that splits collaboration features
(Comments, Documents, People) from execution/audit features (History, Audit Trail) as distinct
tabs — it doesn't merge them into one wall of information.

That split is the design signal worth keeping: *execution/audit data* (diagram, variables, tasks,
history, due dates, involved identities, BPMN source) is squarely in scope for a debugger.
*Collaboration data* (comments, chat, document attachments) and *cluster ops data* (CPU, heap,
connection pools, REST throughput) are not things this tool has or needs — they belong to a
different problem (multi-user case work, and running-server operations) than "why did this one
test's process instance end up in this state."

## Non-goals (explicit rejections, with rationale)

| Rejected feature | Why Flowable has it | Why it doesn't belong here |
|---|---|---|
| Comments / chat / conversation tab | Flowable Work's collaboration layer, backed by a separate content/social service | This tool has no user identity, no persistence beyond the current test method, and no second users to converse with — there is nothing to comment *to* |
| Document attachments | Flowable Work's content-management integration | No content-service dependency exists in this starter's dependency graph, and adding one to a debug tool would violate the "provided-scope-only, zero new runtime dependency" precedent this module already established |
| Cluster/ops dashboards (CPU, heap, DB pool, REST throughput) | Flowable Control monitors a *running server fleet* | A JUnit test process isn't a server fleet; these numbers would describe the test JVM, not the process instance under test — misleading, not useful |
| WebSocket/SSE live push | Neither Flowable UI actually uses it for this; both poll or reload | The existing JS-driven auto-refresh (countdown + pause, scroll/tab-preserving) already solves "stay current" without adding a persistent-connection protocol to a `com.sun.net.httpserver.HttpServer`-based server that has zero other stateful-connection handling today |
| Multi-tenant / user management | Flowable Work and Control both sit behind IDM + tenant scoping | Out of scope by the original design's own three-gate activation model (no auth exists or should exist here) |

## Proposed features, tiered by cost and grounded in verified APIs

### Tier 1 — presentation-only (no `flowable-test-core` changes)

1. **List-page grouping/status summary.** Today `/` is a flat ID list. Query
   `ProcessDiagnosticsCollector.collect(id)` for each tracked ID (already an in-memory H2/PG read,
   cheap at the scale of one test method) and group the list by `processDefinitionKey`, with an
   active/ended badge per row — the same "what's running vs. done" question Control's
   Running/Completed dashboard answers, but scoped to a single test method instead of a cluster
   time range, and without a charting library.
2. **Humanized relative time.** Activity-trail timestamps currently render as raw
   `Instant.toString()` (`2026-07-15T22:16:19.827Z`). A small client-side JS formatter
   (`"2s ago"`, `"3m ago"`) mirrors how both Flowable Work's due-date field and virtually every
   modern dev tool render timestamps — pure presentation, `Instant` data already flows to the page.
3. **Keyboard shortcuts.** `/` focuses the filter box, `1`-`5` switch tabs, `Esc` closes the
   lightbox, `r` forces a refresh. Not something Flowable's own UI documents, but a standard
   "feels like a pro tool" affordance with near-zero implementation cost given the tab-switching
   and lightbox JS already exists.
4. **"Copy diagnostics" button.** `flowable-test-core` already has `ProcessDiagnosticsFormatter`
   (the exact text this starter attaches to a failed test's assertion message) sitting unused by
   the debug UI. Add one small `GET /instances/{id}/diagnostics.txt` route in the debug-ui module
   only, and a copy button next to it — the debug UI and the on-failure diagnostics output become
   the same text a developer can paste into a bug report, instead of two independent renderings of
   the same data drifting apart over time.
5. **Toast-style feedback + subtle transitions.** Replace the current bare `button.textContent =
   'Copied!'` swap with a small transient toast, and fade tab-panel switches instead of a hard
   `display` toggle. No Flowable precedent needed here — this is generic perceived-responsiveness
   polish, cheap given the existing vanilla-JS-only constraint (no animation library, just CSS
   `transition`).

### Tier 2 — additive fields on existing records (source-compatible `flowable-test-core` change)

`ProcessDiagnosticsReport` and its nested records are constructed in exactly one place
(`ProcessDiagnosticsCollector.collect`), so adding record components is a recompile-and-done
change, not a wire-format break — there is no serialized/persisted form of this record anywhere in
the codebase to migrate.

6. **Task due date, priority, and an "overdue" flag.** `TaskInfo.getPriority()` and
   `getDueDate()` are already available on every `Task` the collector's existing
   `taskService.createTaskQuery()` call returns — `pendingTasks()` just doesn't read them yet. Add
   `Instant dueDate` and `int priority` to `PendingTaskInfo`; the UI renders an overdue-red badge
   when `dueDate` is in the past, mirroring Flowable Work's color-coded overdue indicator on its
   task header.
7. **Completed tasks.** `pendingTasks()` only queries *active* tasks
   (`taskService.createTaskQuery()`). `HistoryService.createHistoricTaskInstanceQuery().processInstanceId(id).finished().list()`
   gives `getDurationInMillis()` and `getDeleteReason()` for tasks that already completed —
   distinguishing a task that was genuinely completed from one that was deleted/cancelled is
   exactly the kind of thing a failing test's "why is this task gone" question needs, and it's the
   direct analog of Flowable Work's task History tab.
8. **Involved people, not just candidate groups.** `candidateGroups()` today filters
   `taskService.getIdentityLinksForTask(id)` down to `IdentityLinkType.CANDIDATE` only, discarding
   `ASSIGNEE`/`OWNER`/`PARTICIPANT` links Flowable already computes. Surfacing all of them (as a
   small `List<IdentityLinkInfo(type, userId, groupId)>`) is the direct analog of Flowable Work's
   "People" tab, and needs no new query — just widening an existing filter.
9. **Process definition metadata.** `RepositoryService.getProcessDefinition(id)` exposes `name`
   and `category` beyond the `key`/`version` already shown; `getDeploymentTime()` (on
   `EngineDeployment`, reached via `getDeploymentId()`) adds "deployed when" — useful when a test
   redeploys a process mid-suite and a developer is checking whether the diagram they're looking at
   is actually the version they think it is.

### Tier 3 — new capability, still read-only, still zero new runtime dependencies

10. **BPMN XML source tab.** `RepositoryService.getDeploymentResourceNames(deploymentId)` +
    `getResourceAsStream(deploymentId, resourceName)` retrieve the exact deployed `.bpmn20.xml` —
    Flowable's own tooling (Modeler, and the legacy Admin app this project's original design doc
    already studied) always offers a "view the definition" affordance next to "view the running
    instance." Rendered as a plain, indented `<pre>` block — no client-side XML/syntax-highlighting
    library, consistent with this module's zero-new-dependency precedent.
11. **Parent/child process instance links.** `HistoricProcessInstance.getSuperProcessInstanceId()`
    (available even while the instance is still active — Flowable writes history rows live, not
    only on completion) identifies the parent of a call-activity-spawned instance;
    `RuntimeService.createProcessInstanceQuery().superProcessInstanceId(id)` finds its children.
    Surfacing "part of instance X" / "spawned instances: Y, Z" as links between existing detail
    pages turns what's currently N disconnected pages (when a test's process calls sub-processes)
    into a navigable graph — directly useful given `ProcessInstanceTracker` already tracks every
    instance started during a test method, parent and children alike.

## API verification

Confirmed via `javap` against this repo's own pinned `7.1.0` jars (not assumed from Flowable's
Java 6.8.0-era legacy source the way the original diagram-rendering design doc had to; these are
plain, currently-shipped API calls):

```
TaskInfo.getPriority(): int
TaskInfo.getDueDate(): java.util.Date
TaskInfo.getCreateTime(): java.util.Date
HistoricTaskInstance.getDurationInMillis(): java.lang.Long
HistoricTaskInstance.getDeleteReason(): java.lang.String
IdentityLinkType.{ASSIGNEE,CANDIDATE,OWNER,PARTICIPANT,STARTER,REACTIVATOR}: String constants
RepositoryService.getDeploymentResourceNames(String): List<String>
RepositoryService.getResourceAsStream(String, String): InputStream
RepositoryService.getProcessDefinition(String): ProcessDefinition { getName(), getCategory() }
EngineDeployment.getDeploymentTime(): java.util.Date
HistoricProcessInstance.getSuperProcessInstanceId(): String
ProcessInstanceQuery.superProcessInstanceId(String): ProcessInstanceQuery
```

All confirmed present in `flowable-task-service-api-7.1.0.jar`, `flowable-engine-7.1.0.jar`, and
`flowable-engine-common-api-7.1.0.jar` — the same jars already resolved in this repo's local `.m2`
for the pinned `flowable.version`. Not yet re-verified against the `7.0.0` end of the supported
range (`flowable.supported.min`) — needed before implementation, same caveat the original design
doc already carries for its own API surface.

## Component-level design deltas

| Change | Module | Kind |
|---|---|---|
| `ProcessDiagnosticsReport.PendingTaskInfo` gains `dueDate`, `priority` | `flowable-test-core` | Additive record component |
| New `ProcessDiagnosticsReport.CompletedTaskInfo` record + `List<CompletedTaskInfo> completedTasks` field | `flowable-test-core` | Additive record component |
| `PendingTaskInfo` (or a shared type) gains `List<IdentityLinkInfo>` beyond `candidateGroups` | `flowable-test-core` | Additive, widens existing query filter |
| `ProcessDiagnosticsReport` gains `processDefinitionName`, `processDefinitionCategory`, `deploymentTime` | `flowable-test-core` | Additive record component |
| `ProcessDiagnosticsCollector` reads the above via already-injected `TaskService`/`HistoryService`/`RepositoryService` — **`RepositoryService` is not currently injected**, needs adding to the constructor | `flowable-test-core` | New constructor dependency — check every call site (`FlowableTestDiagnosticsAutoConfiguration`, this module's own tests) |
| New `InstanceListHandler` grouping logic | `flowable-test-debug-ui` | Presentation only |
| New `DefinitionSourceHandler` (`GET /instances/{id}/definition.xml`) | `flowable-test-debug-ui` | New handler, read-only |
| New `DiagnosticsTextHandler` (`GET /instances/{id}/diagnostics.txt`) | `flowable-test-debug-ui` | New handler, reuses `ProcessDiagnosticsFormatter` |
| Cross-instance links (parent/children) on `InstanceDetailHandler` | `flowable-test-debug-ui` | Presentation, needs the new `superProcessInstanceId`/children data from the collector |
| `Layout.STYLE` additions: overdue badge variant, toast component, relative-time JS helper, keyboard-shortcut JS | `flowable-test-debug-ui` | Presentation only |

`ProcessDiagnosticsFormatter` (text-rendering for on-failure diagnostics) is a separate concern from
the HTML rendering in `flowable-test-debug-ui` and is **not** proposed to change shape — only to
gain a second caller (the new `diagnostics.txt` route).

## Risks and open questions

1. **`RepositoryService` is a new constructor dependency on `ProcessDiagnosticsCollector`.** Every
   existing call site that constructs this class (`FlowableTestDiagnosticsAutoConfiguration`, and
   this module's own test fixtures) needs updating — a mechanical but real breaking change to a
   constructor signature, not just an additive one, unlike the record changes above.
2. **Per-row `collect()` calls for the list-page grouping (Tier 1, #1) is O(N) engine queries for N
   tracked instances.** Fine at typical single-test-method scale (a handful of instances), but
   worth confirming there's no pathological test that tracks hundreds of instances in one method
   before shipping this without a cap or lazy-expand-per-group UI.
3. **`7.0.0` API compatibility is unverified.** All API verification above targeted `7.1.0` only;
   the original design doc's own precedent requires checking both ends of
   `[flowable.supported.min, flowable.supported.maxExclusive)` before implementation, not just the
   version currently checked out locally.
4. **Parent/child linkage (Tier 3, #11) — checked, not just assumed.** `ProcessInstanceTracker`
   registers as a `FlowableEventListener` on the engine's own event dispatcher for
   `PROCESS_STARTED` globally (see its class Javadoc and `onEvent`), not just instances the test
   thread explicitly calls `startProcessInstanceByKey` for — so a call-activity-spawned child
   instance fires the same engine-level event and is already tracked, up to
   `maxTrackedProcessInstances`. The "spawned instances" link can safely cross-reference the
   tracker's own list rather than needing a separate engine query. The one residual edge case: a
   test that starts enough instances to hit the cap could have an untracked (and thus unlinkable)
   child — same limitation `omittedProcessInstanceCount()` already surfaces for the plain instance
   list today.
5. **Zero code and zero tests exist for any of this** — same caveat the original design doc states
   for itself: this is a paper design grounded in checked facts, not a validated implementation.

## Suggested implementation order

1. Tier 1, items 1–5 — no core-module risk, immediately shippable, delivers most of the
   "feels more engaging" ask on its own.
2. Tier 2, item 6 (due date/priority/overdue) — smallest core change, highest debugging value
   (an overdue task is often exactly why a test hung).
3. Tier 2, items 7–9, together — since all three need the same `RepositoryService` constructor
   change identified in risk #1, batching them avoids touching that constructor twice.
4. Tier 3, item 10 (BPMN source tab) — independent of the Tier 2 batch, can ship in parallel.
5. Tier 3, item 11 (parent/child links) — last, once risk #4 above is resolved.
