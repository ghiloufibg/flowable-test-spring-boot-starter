# Design: debug UI UX enhancements — benchmarked against Flowable's own UIs

Date: 2026-07-16
Status: **Tiers 1, 2, and 3 all implemented and verified end to end in a real browser** (Tier 1 and
the Alpine.js prototype shipped first; Tiers 2-3 — due date/priority/overdue, completed tasks,
involved people, process definition metadata, the BPMN XML source tab, and parent/child instance
links — implemented in one pass, also verified live). API surface verified against both ends of
the supported Flowable range (`7.0.0` and `7.1.0`) via `javap`; feature set grounded in Flowable's
own published documentation (cited inline), not assumed.
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

## Frontend tooling: modern vanilla JS, not a vendored MVC framework

Raised directly: should this module adopt a lightweight client-side MVC framework (Alpine.js,
petite-vue) to keep the growing JS footprint maintainable, with its file vendored into this
module's own resources (avoiding the CDN objection a pure "add a `<script src>`" approach would
raise)? Evaluated on the merits, not dismissed on the CDN point alone.

**The duplication is real.** `InstanceDetailHandler.java` is 418 lines (roughly a third of it one
inline `<script>` block); `InstanceListHandler.java` is 152. The "don't hijack keyboard shortcuts
while the user is typing" guard is already copy-pasted near-identically in both
(`InstanceListHandler.java`, `InstanceDetailHandler.java`) — genuine sign of JS sprawl, not a
hypothetical concern.

**What vendoring a framework would actually cost, concretely, against this server as it exists
today:** `DebugUiServer` currently registers exactly two HTTP contexts (`/`, `/instances/`), both
dynamic — there is no static-file-serving capability at all. Shipping a vendored `alpine.min.js`
"from the same location as our code" forces a choice neither option is free:

- Serve it properly (a new `StaticResourceHandler`, a new `/static/*` context, classpath-resource
  reading with path-traversal safety, `Content-Type`/cache headers) — a genuinely new component,
  not a one-line addition.
- Or inline the vendored source as a Java string the way `Layout.STYLE` inlines CSS today — but
  that repeats a 7-17KB payload (Alpine.js gzipped ballpark; petite-vue is smaller, ~7KB) on
  *every* response, and `InstanceDetailHandler`'s page already auto-refreshes every 3 seconds by
  design. Fine for ~2KB of CSS: the wrong pattern for a framework-sized script on a 3-second cycle.

Either path also starts a maintenance obligation this repo has no process for yet: the Java side
has a real, working version-pinning discipline (`flowable.version`, checked across a CI matrix
spanning the supported range) — vendoring a JS dependency starts that same obligation (which
version, why, how it gets bumped, what breaks if it doesn't) from zero, with no JS test coverage
to catch a bad bump.

**Match tool to actual complexity.** The UI is two pages, ~5 tabs, two filter boxes, no
cross-page reactive state, no component tree of meaningful depth. A reactive framework earns its
keep when a UI has many small interdependent widgets; this one doesn't — even the parent/child
instance linking proposed under Tier 3 is anchor tags between two already-rendered pages, not
shared client state.

**Decision: modern vanilla JS, no framework, framework question deferred (not rejected).**
Concrete, low-risk wins available today with zero new dependency:

- `Intl.RelativeTimeFormat` (built into every evergreen browser) directly replaces the hand-rolled
  `flwFormatRelativeTime` in `InstanceDetailHandler.java` — locale-aware "2 minutes ago" for free,
  one function deleted.
- `class`-based page controllers replace the current `flw`-prefixed global functions/variables
  (`flwActiveTab`, `flwShowTab`, `flwPaused`, ...) — that prefix exists purely to fake namespacing
  in the absence of real encapsulation; a class gives it for free.
- Optional chaining (`el?.textContent = ...`) cleans up the several `if (el) el.x = ...` guards
  already present.
- A shared `Layout.SCRIPT` constant (mirroring the existing `Layout.STYLE` constant for CSS)
  extracts the duplicated keyboard-guard/filter logic into one place both handlers include —
  fixing the actual duplication found above without introducing anything new to license, version,
  or serve statically.

**Revisit trigger, stated explicitly so this isn't re-litigated from scratch:** if a future
feature needs genuine cross-page *reactive* state (not just "one more tab" or "one more filter
box"), reopen this decision — and build the static-file-serving capability as part of that
decision, not as a speculative prerequisite now.

### Prototype: Option B actually built and compared, not just analyzed

Asked directly to build the vendored-framework path before deciding, rather than settle the
question on analysis alone. Built for real, on `InstanceDetailHandler` specifically (the more
complex of the two pages — tabs, toast, lightbox, refresh countdown, keyboard shortcuts, variable
filtering), while `InstanceListHandler` was left as plain vanilla JS, giving one running
application with both approaches side by side for a fair, working comparison rather than a
throwaway spike.

**What got built**: Alpine.js `3.15.12` (MIT, verified against its published `LICENSE.md`)
downloaded once and vendored at `flowable-test-debug-ui/src/main/resources/static/alpine-3.15.12.min.js`
(46,346 bytes; license text stored alongside as `alpine-3.15.12.LICENSE.md`), served through a new
`StaticResourceHandler` (`GET /static/{fileName}`, filename restricted to `[A-Za-z0-9._-]+` so
`../` traversal isn't a resolvable input, `Cache-Control: public, max-age=31536000, immutable`
since the version is baked into the filename). `InstanceDetailHandler`'s interactive chrome was
rewritten as a single `x-data="flwDebugPage()"` component on `<body>`: `x-show`/`:class` bindings
replace every `document.querySelectorAll(...).forEach(...)` class-toggle loop, `x-transition`
replaces the hand-written `@keyframes flwFadeIn` CSS. All server-rendered data (variables table,
task cards, activity trail, failed jobs) was left untouched — only the interactivity layer changed,
per Alpine's own "sprinkle it on top of server-rendered HTML" design intent.

**Verified working end to end in a real browser** (not just read from source): tab switching,
pause/resume, the diagram lightbox, copy-to-clipboard with toast feedback, the `/`, `1`-`5`, `Esc`,
`r` keyboard shortcuts (including the typing-guard — shortcuts correctly stay inert while a search
box has focus), and the vendored file serving correctly from `/static/` with the intended cache
headers (confirmed via the actual response headers, not assumed).

**Findings:**

- **Genuinely more declarative for the chrome it touched.** Tab switching is now `:class="{
  'flw-tab-btn-active': activeTab === 'diagram' }"` instead of a manual
  `querySelectorAll(...).forEach(el => el.classList.remove(...))` pair — this is a real
  readability win exactly where duplication was found earlier (each tab button's active state used
  to require two synchronized DOM-query loops; now it's one reactive expression per button).
- **Line count is a wash, not a reduction.** `InstanceDetailHandler.java` went from 418 to 408
  lines — Alpine's declarative bindings save lines in some places but the `x-data` object plus
  directive attributes (`:class`, `x-show`, `@click`, `x-transition`) add them back elsewhere.
  Adopting Alpine is a readability/maintainability trade, not a size win.
- **`x-transition`'s default doesn't respect `prefers-reduced-motion` out of the box** — the
  vanilla version's CSS had an explicit `@media (prefers-reduced-motion: reduce)` guard around the
  fade animation; Alpine's default transition has no equivalent without extra configuration
  (`Alpine.data` hooks or manually conditioning the transition classes). This is a real regression
  found by building the prototype, not something the analysis above anticipated, and would need
  fixing before this could ship as-is.
- **The static-serving cost was real but one-time.** `StaticResourceHandler` is 55 lines — not
  large, but it's 55 lines and one new HTTP route this module didn't need before, confirmed exactly
  as predicted rather than hypothesized.
- **No regressions**: `DebugUiServerEndpointTest` and the rest of the existing suite pass unchanged
  against the Alpine-powered page, since those assertions only check for content substrings, not
  markup structure.

**Conclusion — analysis held up under an actual build:** the framework earns real, visible
readability gains exactly where the duplication problem was (declarative tab/visibility state), at
the cost of one new component (`StaticResourceHandler`), a new vendored-dependency process, and one
concrete behavioral gap (`prefers-reduced-motion`) that would need closing before shipping. That
matches the original recommendation's shape — a framework is a genuine, non-hypothetical option for
this codebase, not a bad fit — but the prototype didn't surface anything that overturns "defer, on
the stated trigger" as the better default: the win is real but is scoped to the *chrome*, and the
duplication the framework would fix is fixable in vanilla JS for the reasons already stated. The
prototype code is intentionally left in the working tree (not deleted) as a reference for
whichever direction is chosen next.

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

## Risks and open questions (resolved during implementation)

1. **Resolved.** `RepositoryService` is now a constructor dependency on `ProcessDiagnosticsCollector`.
   All five call sites (`FlowableTestDiagnosticsAutoConfiguration` plus four test files in
   `flowable-test-autoconfigure`) were updated; all already had `RepositoryService` autowired for
   other reasons, so no new test wiring was needed.
2. **Not yet mitigated, still a real limitation.** Both the list-page grouping (Tier 1) and
   parent/child lookup (Tier 3, #11) are O(N) `collect()` calls for N tracked instances. Still fine
   at typical single-test-method scale; no cap or lazy-expand UI added. Revisit if a real test
   suite tracking hundreds of instances per method surfaces this as a real slowdown.
3. **Resolved.** Every API used by Tier 2/3 was re-verified against `flowable-*-7.0.0` jars via
   `javap` (`TaskInfo`, `HistoricTaskInstance`, `IdentityLinkType`, `RepositoryService`,
   `ProcessDefinition`, `EngineDeployment`, `HistoricProcessInstance`,
   `ProcessInstanceQuery.superProcessInstanceId`) — identical signatures at both ends of the
   supported range.
4. **Confirmed correct in code, not yet exercised live.** The `ProcessInstanceTracker`
   global-event-listener reasoning held up in code review, but no call-activity fixture exists in
   this module's test resources to exercise it end to end in the browser (only in the passing
   automated test suite, which doesn't cover parent/child rendering specifically). Worth adding a
   call-activity fixture if this feature needs stronger confidence later.
5. **Resolved.** Fully implemented, compiled, and verified live in a browser (see "Verification
   note" below) — no longer a paper design.

## Suggested implementation order (all done)

1. ~~Tier 1, items 1–5~~ — shipped first (list grouping, relative time, keyboard shortcuts, copy
   diagnostics, toast/transitions), followed by the Alpine.js prototype (see "Frontend tooling"
   above).
2. ~~Tier 2, item 6 (due date/priority/overdue)~~ — `PendingTaskInfo` gained `dueDate`, `priority`;
   the detail page renders a red "overdue" badge when the due date is in the past.
3. ~~Tier 2, items 7–9~~ — implemented together against the same new `RepositoryService`
   constructor dependency on `ProcessDiagnosticsCollector`, exactly as risk #1 anticipated:
   completed tasks (new `Tasks` tab), full identity links beyond candidate groups (owner,
   participant, candidate *users* — not just groups), and process definition name/category/
   deployment time in the header.
4. ~~Tier 3, item 10 (BPMN source tab)~~ — a toggle inside the existing Diagram tab (not a
   separate tab), lazy-fetched from the new `GET /instances/{id}/definition.xml` route on first
   click.
5. ~~Tier 3, item 11 (parent/child links)~~ — `superProcessInstanceId` surfaced on the report;
   child instances found by cross-referencing `ProcessInstanceTracker`'s tracked list, confirmed
   safe by risk #4's code-level check (below).

**Verification note**: every feature above was confirmed in a real running browser session (not
just compiled/tested) — the overdue badge, priority, due date, and identity-link badges (including
the "candidate user vs. candidate group" distinction, which needed a follow-up fix after the first
pass silently dropped candidate-user links), the process definition subtitle and deployed time, the
BPMN XML source toggle fetching real deployed XML, and the Completed tasks tab rendering a real
completed task's duration and status. Parent/child lineage rendering was verified by code review and
the passing test suite rather than a live call-activity scenario, since no such fixture exists in
this module's test resources yet.
