# Design: Automatic BPMN diagnostics on test failure

Date: 2026-07-14
Status: Proposed
Relates to: `ProcessTestHarness`, `ProcessInstanceAssert`, `FlowableProcessTest`,
`FlowableTestAssertionsAutoConfiguration`, `flowable-test-starter-design.md` section 4.4

## Problem

Today, when a `@FlowableProcessTest` fails, the consumer sees exactly what JUnit/AssertJ give them:
a message like `Expected process instance <a1b2> to have ended, but it is still active` and a stack
trace. That tells them *that* something is wrong but not *what the process was actually doing* —
which activity it's stuck at, what its variables held at the moment of failure, whether an async
step already blew up on a job-executor thread and got silently parked as a dead-letter job. The
consumer's next step is always the same manual detour: re-run under a debugger, or add throwaway
`System.out.println(runtimeService.getVariables(...))` calls, or query the H2/Postgres tables by
hand. None of that is specific to any one project — it is pure Flowable-engine introspection that
this starter is in a unique position to automate, since it already sits between the test and the
consumer's engine beans on every failure path.

**Scope**: this is diagnostics for BPMN process state only — current activity, variables, activity
history, pending tasks, and async job failures. It does not extend to Kafka message logs or HTTP
mock request logs; those already have their own test-scoped visibility (`KafkaTestBridge`,
WireMock's own request journal) and are a different concern from "what was the process doing."

## Recommended approach: three complementary layers, all built on one collector

A single `ProcessDiagnosticsCollector` (new, `flowable-test-core`) is the only place that knows how
to turn a process instance ID into a diagnostics snapshot. Two independent trigger points feed it,
because no single trigger point covers everything a consumer means by "the test failed or there was
an exception":

| Layer | Trigger | Knows *which* process instance? | Covers |
|---|---|---|---|
| A. Assertion-level enrichment | `ProcessInstanceAssert` / `ProcessTestHarness` throw | Yes — exact, it's the method's own argument | Failures raised by this starter's own assertions/harness (the common case) |
| B. Test-level safety net | JUnit 5 `TestWatcher.testFailed` | No — must be told | Everything else: a plain `assertEquals` on a fetched variable, an NPE in consumer test code, an exception propagated from a delegate |

Layer B needs a way to know which process instances existed in the failing test without the test
author doing anything special. That's the third piece:

| Component | Role |
|---|---|
| C. `ProcessInstanceTracker` | A Flowable engine event listener recording every process instance ID started during the current test method, reset at the start of each test |

### Why a tracker instead of just querying the engine at failure time

The obvious shortcut — at failure time, ask `RuntimeService`/`HistoryService` for "all process
instances" — breaks under this project's own established DB-sharing modes. Both
`embedded-postgres-instance-scope-design.md` (`instance-scope: shared`) and
`kafka-shared-broker-context-isolation-design.md` describe configurations where the same underlying
database and engine can legitimately be reused across multiple test classes/contexts in one JVM. A
naive "list all historic process instances" at failure time would pull in instances left behind by
an unrelated, already-finished test class, producing noisy, misleading diagnostics. A tracker that
is explicitly reset in `beforeEach` and only records what happened between then and the failure is
correct under every DB-sharing mode this starter supports, without needing to know which mode is
active.

## Component design

### `flowable-test-core` (domain-agnostic, no new Spring dependency beyond what's already there)

```
com.flowabletest.core.diagnostics
├── ProcessDiagnosticsReport      (record — the snapshot for one process instance)
├── ProcessDiagnosticsCollector   (RuntimeService/TaskService/HistoryService/ManagementService -> report)
├── ProcessDiagnosticsFormatter   (report(s) -> human-readable text block)
├── ProcessInstanceTracker        (Flowable engine event listener; records + resets process instance IDs)
└── FlowableProcessDiagnosticsExtension (JUnit 5 extension: reset on beforeEach, dump on testFailed)
```

**`ProcessDiagnosticsReport`** — one snapshot, everything needed to explain "what was this process
doing":

```java
public record ProcessDiagnosticsReport(
    String processInstanceId,
    String processDefinitionKey,
    int processDefinitionVersion,
    String businessKey,
    boolean active,
    List<ActivityInfo> currentActivities,      // empty if ended
    Map<String, Object> variables,              // truncated per-value, see below
    List<ActivityTrailEntry> activityTrail,     // chronological, capped
    List<PendingTaskInfo> pendingTasks,
    List<FailedJobInfo> failedJobs) {

  public record ActivityInfo(String activityId, String activityName, String activityType) {}

  public record ActivityTrailEntry(
      String activityId, String activityName, Instant startTime, Instant endTime) {}

  public record PendingTaskInfo(
      String taskId, String name, String assignee, List<String> candidateGroups) {}

  public record FailedJobInfo(
      String jobId, String elementId, String exceptionMessage, String exceptionStacktrace,
      int retriesRemaining) {}
}
```

**`ProcessDiagnosticsCollector.collect(String processInstanceId)`** — builds the report:
- **active + currentActivities**: query `RuntimeService` for the instance's live executions; if
  none exist, the instance has ended (`active = false`, `currentActivities` empty).
- **variables**: `RuntimeService.getVariables(processInstanceId)` while active; falls back to
  `HistoryService.createHistoricVariableInstanceQuery()` once ended, since the runtime execution is
  already gone by then.
- **activityTrail**: `HistoryService.createHistoricActivityInstanceQuery().processInstanceId(...).orderByHistoricActivityInstanceStartTime().asc()`,
  capped at `flowable.test.diagnostics.max-activity-trail-entries` (default 20, keep the *last* N
  so a long-running process still shows what happened right before the failure, not the start).
- **pendingTasks**: `TaskService.createTaskQuery().processInstanceId(...)`.
- **failedJobs**: `ManagementService.createDeadLetterJobQuery().processInstanceId(...)`, each
  enriched with `ManagementService.getDeadLetterJobExceptionStacktrace(jobId)`. This is the single
  highest-value field in the whole report: an async service task that throws does **not** fail the
  test directly — Flowable retries it and eventually parks it as a dead-letter job while the test
  thread just sees a timeout waiting for a task/end event that will now never come. Without this
  field, that failure mode reads as an unexplained hang; with it, the real exception and stack trace
  are surfaced inline.

**Value truncation**: variable values and job stack traces are rendered through a bounded formatter
— `flowable.test.diagnostics.max-variable-value-length` (default 500 chars); byte arrays are shown
as `byte[<length>]`, not dumped raw. This is a plain robustness concern (a process variable holding
a large JSON payload or a PDF byte array must not blow up log output), not a security/redaction
feature — this starter's test data is not production data.

**`ProcessDiagnosticsFormatter.format(List<ProcessDiagnosticsReport>)`** — a pure function
producing one plain-text block, e.g.:

```
===== Flowable process diagnostics (test failed) =====
Process instance a1b2c3 — orderProcess:3 — businessKey=ORDER-42 — ACTIVE
  Current activity: userTask "Approve order" (id=approveOrder)
  Variables:
    orderId = 42
    amount = 199.99
    approved = null
  Activity trail (last 20):
    startEvent -> serviceTask "Validate order" (118ms) -> exclusiveGateway "Needs approval?"
    -> userTask "Approve order" (still active, started 00:00:02.310 ago)
  Pending tasks:
    - "Approve order" id=task-77 candidateGroups=[managers]
  Failed jobs (dead letter): none
========================================================
```

**`ProcessInstanceTracker`** — implements Flowable's `FlowableEventListener`, registered on
`PROCESS_STARTED`:

```java
public final class ProcessInstanceTracker implements FlowableEventListener {
  private final List<String> processInstanceIds = new CopyOnWriteArrayList<>();

  @Override
  public void onEvent(FlowableEvent event) {
    if (event instanceof FlowableEngineEntityEvent entityEvent) {
      processInstanceIds.add(entityEvent.getProcessInstanceId());
    }
  }

  public void reset() { processInstanceIds.clear(); }
  public List<String> trackedProcessInstanceIds() { return List.copyOf(processInstanceIds); }
  // isFailOnException() -> false: a bug in the tracker must never fail the process/test itself
}
```

`CopyOnWriteArrayList` because `PROCESS_STARTED` can legitimately fire from a job-executor thread
(async continuations, call activities) rather than the test thread.

**`FlowableProcessDiagnosticsExtension implements BeforeEachCallback, TestWatcher`**:
- `beforeEach`: resolve `ProcessInstanceTracker` from the Spring context (`SpringExtension
  .getApplicationContext(context).getBeanProvider(ProcessInstanceTracker.class).getIfAvailable()`)
  and call `reset()`. Resolution is defensive (`getIfAvailable`, never a hard bean lookup) so a
  consumer who has disabled diagnostics via configuration property, or an early context-refresh
  failure before beans exist, degrades to a silent no-op rather than a second, unrelated failure
  masking the original one.
- `testFailed(context, cause)`: resolve the collector + tracker the same defensive way; for each
  tracked process instance, `collect(...)`, format the batch, and attach it to the *original*
  failure via `cause.addSuppressed(new ProcessDiagnosticsAttachment(formatted))` — a `RuntimeException`
  subclass with `fillInStackTrace()` overridden to a no-op, so it carries only the diagnostics text
  with no stack trace of its own cluttering the output. Suppressed exceptions are printed by
  standard JUnit/Surefire/IDE stack-trace renderers automatically, so this requires no reporting
  plugin, no custom listener registration in the consumer's build, and shows up exactly where a
  developer already looks first.
- Diagnostics collection itself is wrapped in try/catch inside the extension: a failure while
  *collecting* diagnostics is logged at WARN and swallowed, never rethrown — it must never replace
  or suppress visibility into the original test failure.

This extension is added to the existing composed annotation, the same way `@ActiveProfiles` and
`@SpringBootTest` already are — no new annotation for consumers to add:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(FlowableProcessDiagnosticsExtension.class)   // NEW
public @interface FlowableProcessTest { ... }
```

### Layer A: enrichment at the point of assertion (no new component, an edit to existing ones)

`ProcessInstanceAssert` and `ProcessTestHarness` already know the exact process instance ID at the
moment they're about to fail — there's no ambiguity to resolve, so this layer doesn't need the
tracker at all. Each `failWithMessage(...)` call (AssertJ) and each thrown `AssertionError`/
`IllegalStateException` (harness) appends the single-instance diagnostics block to its own message,
via the same `ProcessDiagnosticsCollector`/`ProcessDiagnosticsFormatter` pair. This is strictly more
precise than layer B for the failures it covers (it can never attach the wrong process instance's
state), and it means the diagnostics appear in the primary exception message, not only as a
suppressed addendum. Layers A and B overlapping for the same process instance on the same failure is
expected and harmless — redundant information, not incorrect information.

### `flowable-test-autoconfigure`

New `FlowableTestDiagnosticsAutoConfiguration`, following the exact shape of
`FlowableTestAssertionsAutoConfiguration` (`@ConditionalOnBean(ProcessEngine.class)`, always active,
no optional third-party dependency to gate on):

```java
@AutoConfiguration(afterName = {
    "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
    "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration"})
@ConditionalOnBean(ProcessEngine.class)
@ConditionalOnProperty(prefix = "flowable.test.diagnostics", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(FlowableTestDiagnosticsProperties.class)
public class FlowableTestDiagnosticsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ProcessInstanceTracker processInstanceTracker(ProcessEngine processEngine) {
    var tracker = new ProcessInstanceTracker();
    processEngine.getProcessEngineConfiguration()
        .getEventDispatcher()
        .addEventListener(tracker, FlowableEngineEventType.PROCESS_STARTED);
    return tracker;
  }

  @Bean
  @ConditionalOnMissingBean
  ProcessDiagnosticsCollector processDiagnosticsCollector(
      RuntimeService runtimeService, TaskService taskService,
      HistoryService historyService, ManagementService managementService,
      FlowableTestDiagnosticsProperties properties) {
    return new ProcessDiagnosticsCollector(
        runtimeService, taskService, historyService, managementService, properties);
  }
}
```

`@ConditionalOnProperty(..., matchIfMissing = true)` makes this **on by default** — consistent with
assertions/harness being always-active — but a consumer can turn it off wholesale
(`flowable.test.diagnostics.enabled: false`) if they have their own failure-reporting tooling and
consider this noise.

## Configuration

```yaml
flowable:
  test:
    diagnostics:
      enabled: true                      # default true
      max-activity-trail-entries: 20     # default 20
      max-variable-value-length: 500     # default 500
      include-failed-jobs: true          # default true
```

Same shape as the other opt-in-property precedents in this starter (`flowable.test.kafka.broker-scope`,
`flowable.test.http-mocks.services`): zero-config default path is unchanged/always-on, every knob is
a narrow override.

## Edge cases

- **Ended process instances**: `RuntimeService` has nothing once a process instance completes, so
  every field falls back to the historic equivalent (`HistoricVariableInstanceQuery`,
  `HistoricActivityInstanceQuery`). A test failing with `hasEndedAt("wrongActivity")` still gets a
  full report even though the instance is gone by the time the assertion runs.
- **Parallel gateways / multiple concurrent active activities**: `currentActivities` is a list, not
  a single value, precisely for this case.
- **Async job failure racing the test thread**: the dead-letter-job lookup is not time-bounded —
  by the time the test thread's own poll/assertion times out, Flowable's own job-retry backoff has
  normally already exhausted, so the dead-letter row exists. If it hasn't yet (a very short test
  timeout raced ahead of the retry backoff), `failedJobs` is simply empty for that report; this is a
  known, acceptable gap, not a correctness bug — the alternative (blocking diagnostics collection
  until retries exhaust) would make every failure slower without a way to bound how long to wait.
- **Diagnostics-collection failure**: never allowed to replace the original failure (see extension
  behavior above) — worst case, the consumer gets the original stack trace with no diagnostics
  attached, exactly today's behavior.

## Explicitly out of scope

- Kafka message logs, HTTP mock request/response logs — separate, already-covered concerns
  (`KafkaTestBridge`, WireMock's own admin API), not duplicated here.
- Any redaction/masking of variable values — this is test data, not production data; truncation is
  for output size, not confidentiality.
- A machine-readable (JSON) report format — the only consumer of this output is a human reading a
  failed test's console/report output; adding a second structured format is speculative until a
  concrete need (e.g., CI dashboard ingestion) appears.
