# Design: Shared Kafka Broker Across Test Classes (replacing `reuseForks=false`)

Date: 2026-07-13 (implemented 2026-07-14)
Status: Implemented
Relates to: `flowable-test-example/pom.xml:108-122`, `EmbeddedFlowableKafkaSupport`,
`EmbeddedPostgresSupport` (same "one shared resource, many isolated contexts" goal, applied to
Kafka instead of Postgres)

## Problem

`flowable-test-example` sets `reuseForks=false`, forcing a brand-new JVM per test class. The
comment in the pom is accurate: the embedded Kafka broker (`EmbeddedFlowableKafkaSupport`) is a
JVM-wide singleton, started at most once per JVM. If Surefire reused one JVM across
`OrderAutoApprovedFlowTest`, `OrderManualReviewFlowTest`, and `PaymentCallbackEventRegistryTest`,
Spring's test-context cache would keep multiple `ApplicationContext`s alive simultaneously (two of
the three classes differ by `@MockExternalService`, which is a distinct cache key), each with its
own `ProcessEngine`.

**Root cause is narrower than "different `@MockExternalService` configs" — verified, not
assumed**: `order-processing.bpmn20.xml:87-92` embeds the payment-callback event-registry
subprocess directly inside the *one* shared BPMN process. Every context's `ProcessEngine`
independently deploys that same process on startup, so **every** engine — regardless of which
test class booted it, and even if all three happened to share one cached context — activates its
own Kafka consumer on the `payment-callbacks` topic using the literal, hardcoded `groupId` from
`payment-callback-inbound.channel:8` (`"flowable-test-example-payment-callback-group"`). Two
engines alive at once in the same JVM means two consumer instances in the *same* consumer group
reading the *same* topic on the *same* shared broker — genuine competing consumers, where Kafka
hands each message to only one of them. A callback meant for one engine's process instance can be
silently stolen by the other, causing correlation to fail intermittently.

**This is not about two test classes executing concurrently — Surefire already runs them strictly
sequentially.** It's about context *lifetime* outliving test *execution*. Spring's
`TestContextCache` keeps every distinct `ApplicationContext` it builds resident for the rest of the
JVM's life (only evicted on LRU cache overflow — default max size 32, far above the handful of
distinct configs here — or at JVM shutdown); moving to the next test class does not close the
previous one's context. Concretely: `OrderAutoApprovedFlowTest` runs, builds Context 1, whose engine
activates a `payment-callbacks` consumer in the hardcoded group — then the class finishes but
Context 1 stays cached and that consumer thread keeps polling. `OrderManualReviewFlowTest` runs
next with a different `@MockExternalService` config (cache miss), builds a brand-new Context 2,
whose engine activates its *own* consumer in the *same* group on the *same* topic — while
Context 1's consumer is still running in the background. Kafka's rebalance now has two group members
contending for one partition, and hands it to whichever wins the rebalance — possibly the stale
Context 1 consumer, leaving Context 2's test waiting on a callback that never arrives.

`reuseForks=false` avoids this by construction: a fresh JVM means a fresh (empty) singleton
broker with exactly one engine ever attached to it. It works, but it pays a full
JVM-start-plus-Spring-context-boot cost per class — the opposite of what the singleton broker
was built to avoid.

## Why this is not the same shape as the Postgres problem

`EmbeddedPostgresSupport`'s `shared` instance-scope mode solves an analogous-looking problem for the
DB by keeping one server process and handing each context a fresh, uniquely-named logical database. It
is tempting to want the same shape here — one broker, one uniquely-named logical Kafka "namespace"
per context — but the two isolation units are not equivalent:

- A Postgres database name is a **connection-time parameter**. The test infrastructure picks it,
  hands it to the driver, and the consumer's schema/business logic is completely unaware of what
  it's called. Renaming it changes nothing the consumer owns.
- A Kafka topic name and consumer group id are **baked into the consumer project's own Event
  Registry channel descriptor** (`payment-callback-inbound.channel`) — a business/domain artifact,
  not test-infrastructure config. Randomizing them per test context means either rewriting the
  consumer's channel JSON at test time (the starter reaching into and mutating a project artifact
  it must otherwise treat as opaque, plus no guarantee Flowable's channel deployer even honors a
  runtime-injected override of a literal JSON string field), or patching Flowable's internal
  channel-to-consumer wiring directly (undocumented internals, not the stable `provided`-scope
  surface this starter deliberately limits itself to — see `CLAUDE.md`'s Flowable version-isolation
  section).

Both routes cross a scope boundary this project has avoided elsewhere. So true per-context
*namespace* isolation, matching Postgres exactly, isn't the right target here.

## Recommended approach: two modes gated by an explicit property, not auto-detection

Two genuinely different execution shapes need two different strategies — trying to serve both
with one mechanism is where the earlier draft of this design (an "activation registry" that
guessed which context should currently own the broker) got more complicated than it needed to be.
Split it explicitly instead, the same way the Postgres design split `per-context`/`shared` behind
one property rather than one bean trying to do both:

```yaml
flowable:
  test:
    kafka:
      broker-scope: shared          # NEW. shared (default) | per-context
```

| Value | Behavior |
|---|---|
| `shared` (default) | **Unchanged broker**, `EmbeddedFlowableKafkaSupport`'s existing JVM-wide singleton. New: each Spring context's Flowable-managed inbound Kafka listener container(s) are started when that context's test class begins and stopped when it ends (see lifecycle mechanism below), so at most one context's consumers are ever polling at a time. |
| `per-context` | Each Spring context gets its own freshly-started `EmbeddedKafkaBroker` (own random port), never shared with another context. No start/stop choreography needed — full isolation makes it unnecessary. A standalone resource-allocation choice, same as Postgres's `instance-scope`: pick it whenever guaranteed isolation matters more than the cost of an extra broker per context, for whatever reason the consumer has — not conditioned on any particular test-framework feature being enabled. |

### Why `shared` mode doesn't need topic or consumer-group cleanup — only container start/stop

The instinct to "clean Kafka topics and consumers when a class finishes" is reasonable but turns
out to be more than is needed, and doing it (deleting/recreating topics, resetting committed
offsets) would add real risk for no correctness benefit:

- Kafka already scopes committed offsets **per consumer group**, not per topic. When Context 2's
  engine starts a new consumer under the exact same hardcoded `groupId` that Context 1's engine
  used, it resumes from Context 1's last *committed* offset — it does not replay the whole topic
  from the beginning, and it does not see anything published after it becomes the sole active
  member. Leaving the topic and the group's offsets alone already gives the right behavior; the
  only thing that ever caused cross-talk was *two* consumer instances live in that group
  *simultaneously*, which stopping Context 1's container before Context 2's starts fully prevents.
- The one residual edge case — a message Context 1 published-but-never-committed-as-consumed
  right before its class ended — gets redelivered to Context 2's engine on start. This is
  functionally a no-op: Flowable's event-registry correlation looks for a running process instance
  matching the message's correlation key (`orderId`); Context 2 has no such instance for an order
  that belongs to Context 1's test, so it's silently ignored. Worth documenting as expected
  behavior, not worth building topic/offset resets to eliminate.
- Deleting/recreating the topic would additionally fight with `EmbeddedFlowableKafkaSupport`'s own
  topic bookkeeping (it tracks what it already created) and with any in-flight rebalance from the
  container just stopped — added moving parts for a problem that container start/stop already
  solves.
- The existing outbound-side path (`KafkaTestBridge.awaitMessages`, `order-events` topic) is
  already immune to this entire class of problem today, independent of anything in this design: it
  reads with a fresh random consumer group every call (`KafkaTestBridge.java:65`) and filters by
  the specific `order.getId()` in its predicate, so stale messages from an earlier test class are
  simply messages that don't match and get skipped.

### New component: `FlowableKafkaConsumerLifecycleTestExecutionListener`

A `TestExecutionListener` (registered via `META-INF/spring.factories`, merged into the default
list — same discovery mechanism already relied on for the starter's other test-context hooks),
active only when `broker-scope=shared` (the default):

- `beforeTestClass(TestContext)`: idempotently **start** this context's Flowable-managed inbound
  Kafka listener container(s) — a no-op if they're already running (covers the case where Spring
  reused an existing cached context because this class's config matches a previous one, e.g.
  `PaymentCallbackEventRegistryTest` today shares its `@MockExternalService` config with
  `OrderManualReviewFlowTest`).
- `afterTestClass(TestContext)`: **stop** this context's Flowable-managed inbound Kafka listener
  container(s) unconditionally. If the very next class reuses this same cached context,
  `beforeTestClass`'s idempotent start brings it back up before that class's tests run — container
  objects support repeated start/stop cycles, this is standard Spring-Kafka container lifecycle,
  not a one-shot resource.

This setup/teardown-per-class symmetry is a more idiomatic fit for `TestExecutionListener` than the
earlier "registry tracking which context currently owns the broker" idea, and produces the same
net effect for today's strictly-sequential execution: exactly one context's consumers running at
any moment, released as soon as each class's work is done rather than lingering until superseded.

### `broker-scope` is a standalone property, like `instance-scope` — not wired to any test-framework setting

Same design stance as the Postgres property: `broker-scope` is read once, on its own, from the
`Environment`, and its two values are just a resource-allocation tradeoff — cheaper-but-shared vs.
costlier-but-fully-isolated. Nothing in either mode's implementation inspects Surefire's or JUnit's
own configuration, and neither mode requires the consumer to also touch some other framework
setting. A consumer can select `per-context` for any reason: wanting deterministic isolation while
debugging a flaky suite, a policy of avoiding shared mutable test infrastructure altogether, or
running genuine parallel test execution — the property doesn't need to know which.

**Deliberately not auto-detected**, for the same reason the Postgres design chose an explicit
property over runtime inference: there's no single reliable signal library code can read to know
"this JVM is currently running tests in parallel" (Surefire's `forkCount` is a separate-JVM
concern the singleton already handles transparently either way; JUnit 5's
`junit.jupiter.execution.parallel.enabled` can be set via a classpath file *or* a runtime system
property with no classpath trace) — so rather than guess, the consumer states the tradeoff they
want directly, exactly as `flowable.test.datasource.embedded-postgres.instance-scope` already does.

**One honest caveat, not a precondition** (mirrors the equivalent caveat on `EmbeddedPostgresSupport`'s
own `shared` instance-scope mode): `shared` mode's start-one/stop-the-other lifecycle
assumes at most one context's tests execute at a time. That's true of this reactor today — no
`forkCount`/`parallel` Surefire configuration and no `junit-platform.properties` file enabling
JUnit 5 in-JVM parallelism exist anywhere in it (verified). Surefire's own `forkCount > 1` (separate
JVMs) never threatens this assumption regardless — each fork already gets its own independent
singleton broker, since `EmbeddedFlowableKafkaSupport` is JVM-wide, not reactor-wide. If a consumer
later enables genuine in-JVM concurrent test execution while still on `shared` mode, that specific
combination is unsafe and should be flagged in the property's javadoc as a known limitation — the
same way the Postgres design documents its own "`shared` mode + parallel Surefire" caveat, so a
consumer who hits it understands the tradeoff instead of debugging a mystery flake.

### `per-context` mode's mechanics

Reuses the shape already proven for Postgres's default (`per-context`) mode: a fresh
`EmbeddedKafkaBroker` started per Spring context, torn down when that context closes, no sharing
and therefore no lifecycle listener needed at all. More expensive per context (a real broker start
per context instead of one for the whole JVM) — the tradeoff a consumer accepts in exchange for
never having to reason about the `shared`-mode caveat above at all.

### Open implementation question — resolved: YES

**Resolved 2026-07-14, implemented.** Verified by decompiling the actual jars this project depends
on at both ends of its supported range (Flowable 7.0.0 and 7.1.0, identical class shape at both):
Flowable's inbound Kafka Event Registry consumer **is** a genuine, externally stoppable/startable
Spring-Kafka `MessageListenerContainer`, registered into the consumer app's own standard
`org.springframework.kafka.config.KafkaListenerEndpointRegistry` bean —
`org.flowable.eventregistry.spring.kafka.KafkaChannelDefinitionProcessor` (artifact
`org.flowable:flowable-event-registry-spring`) builds a `SimpleKafkaListenerEndpoint` per inbound
channel and calls `endpointRegistry.registerListenerContainer(endpoint, factory, startImmediately)`
— the same registration API `@EnableKafka`'s own `@KafkaListener` post-processor uses. Each
container's id is deterministic: `KafkaChannelDefinitionProcessor.CHANNEL_ID_PREFIX +
channelModel.getKey()` (`CHANNEL_ID_PREFIX` is `public static final String`), and the returned
`MessageListenerContainer` (`extends SmartLifecycle`) supports plain, idempotently-repeatable
`.start()`/`.stop()`/`.isRunning()`.

One nuance baked into the implementation: `KafkaListenerEndpointRegistry` is a single bean shared
with any unrelated `@KafkaListener`s the consumer app might declare, so
`FlowableKafkaConsumerLifecycleTestExecutionListener` calls `.stop()`/`.start()` only on containers
matched via `registry.getListenerContainersMatching(id ->
id.startsWith(KafkaChannelDefinitionProcessor.CHANNEL_ID_PREFIX))`, never on the registry as a
whole.

This required two new compile-time dependencies neither this doc nor the starter previously
declared (verified via `mvn dependency:tree` — neither was reachable before):
`org.springframework.kafka:spring-kafka` (`optional`, `flowable-test-autoconfigure/pom.xml` — the
existing `spring-kafka-test` dependency does *not* transitively pull in plain `spring-kafka`, which
is where `KafkaListenerEndpointRegistry`/`MessageListenerContainer` live) and
`org.flowable:flowable-event-registry-spring` (`provided`, same version-isolation rationale as
`flowable-engine` — this is Flowable's own public API surface, not internal reflection).

## Non-goals

- Does not rename or namespace topics/consumer groups — those stay exactly as the consumer project
  defines them in its own `.channel` files, unchanged, in both modes.
- Does not touch outbound-only channels (e.g. `order-events`) — producers have no competing-consumer
  problem; any number of engines can publish concurrently without correlation risk. `shared` mode's
  lifecycle listener only starts/stops *inbound* Kafka Event Registry consumers.
- Does not attempt cross-JVM sharing in `shared` mode — same JVM-only scope as the existing broker
  singleton.
- Does not attempt to auto-detect parallel execution or any other test-framework setting —
  `broker-scope` is an explicit, standalone property, selected on its own merits, the same way
  `flowable.test.datasource.embedded-postgres.instance-scope` is.

## Fallback if the open question resolves negatively

Moot — the open question resolved positively (see above); `shared` mode's lifecycle listener was
built as designed. Left here only as a record of the contingency that was considered before
implementation: had Flowable's inbound Kafka consumer *not* been externally stoppable,
`reuseForks=false` would have stayed the correct, honest choice for any module mixing distinct
Spring configs with inbound Kafka Event Registry channels — a real JVM-boot cost paid for a real
correctness guarantee, not an oversight.

## Test plan — implemented

- `flowable-test-autoconfigure/src/test/java/.../kafka/FlowableKafkaConsumerLifecycleTestExecutionListenerTest.java`:
  drives `TestContextManager` directly against a `@FlowableProcessTest` fixture class to
  deterministically exercise the exact sequence Spring's `TestContextCache` produces —
  `beforeTestClass` (container running), a repeated idempotent `beforeTestClass` (still running),
  `afterTestClass` (stopped), then a final `beforeTestClass` (restarted) — rather than relying on
  real multi-class JUnit execution order, which Surefire/JUnit don't guarantee.
- `flowable-test-autoconfigure/src/test/java/.../kafka/FlowableKafkaBrokerScopePerContextTest.java`:
  drives `SpringApplicationBuilder` directly (not `ApplicationContextRunner`, which bypasses the
  `EnvironmentPostProcessor` SPI where `broker-scope` is actually read) to boot two independent
  contexts back to back and assert they get different `EmbeddedKafkaBroker` instances.
- `flowable-test-example`: `reuseForks=false` removed from `pom.xml`; all three existing test
  classes (`OrderAutoApprovedFlowTest`, `OrderManualReviewFlowTest`, `PaymentCallbackEventRegistryTest`)
  pass in one shared JVM/fork under `broker-scope=shared` (the default) — including
  `PaymentCallbackEventRegistryTest`, the class exercising the actual competing-consumer scenario
  this design fixes.

## Documentation updates — done

- `flowable-test-example/pom.xml`: `reuseForks=false` removed, replaced with a comment pointing to
  this design doc.
- README: `flowable.test.kafka.broker-scope` documented alongside
  `flowable.test.datasource.embedded-postgres.instance-scope`.
