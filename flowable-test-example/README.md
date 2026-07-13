# flowable-test-example

A real Spring Boot + Flowable + Kafka Event Registry order-processing app, depending on
`flowable-test-spring-boot-starter` (test scope) exactly the way any external consumer project
would. It is not part of the starter's own release artifacts — it exists purely to validate the
starter end to end against a genuine domain, closing a gap the starter's own internal test suite
(`flowable-test-autoconfigure`) cannot: that suite only proves channel-file scanning and raw Kafka
producer/consumer bridging in isolation. It never deploys a real BPMN process with a
`flowable:type="send-event"` service task or an event-registry-correlated start event and lets
Flowable itself drive the Kafka traffic. This module does.

## What it proves

| Starter capability | How this module exercises it |
|---|---|
| Embedded database auto-detection | The `Order` JPA repository runs against whichever datasource the starter picks — `io.zonky.test:embedded-postgres` is on this module's test classpath, so tests exercise the Postgres path, not just H2. |
| Kafka topic auto-discovery | No `@EmbeddedKafka(topics = ...)` anywhere — topics come from `src/main/resources/eventregistry/*.channel`. |
| Outbound Kafka Event Registry | `order-processing.bpmn20.xml`'s `publishOrderCreatedEventTask` (`flowable:type="send-event"`) really publishes to the `order-events` topic; `OrderAutoApprovedFlowTest` asserts the message via `KafkaTestBridge`. |
| Inbound, correlated Kafka Event Registry | The embedded "Payment Callback Received" event subprocess consumes the `payment-callbacks` topic and correlates by `orderId` to the right running process instance — no `@KafkaListener` anywhere. `PaymentCallbackEventRegistryTest` publishes a callback message and asserts the `Order` row flips to `PAID`. |
| Declarative HTTP mocking (convention) | `FraudCheckDelegate` makes a real HTTP call; the default stub lives at `src/test/resources/httpmocks/fraud-check-service/mappings/approve.json`. |
| `@MockExternalService` override | `OrderManualReviewFlowTest` / `PaymentCallbackEventRegistryTest` redirect to `httpmocks/fraud-check-service-manual-review` to force the fraud check to decline. |
| `ProcessTestHarness` / `KafkaTestBridge` | Used throughout instead of hand-rolled `RuntimeService`/`TaskService`/Kafka producer-consumer boilerplate. |

Deliberately **zero** `src/test/resources/application.yml`: no hand-configured H2 URL, no
`spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}`, no manual topic list. Every one
of those would normally be hand-maintained boilerplate in a Flowable + Kafka test suite; here the
starter supplies all of it.

## Running it

```
mvn -pl flowable-test-example -am test
```

or, against the oldest end of the supported Flowable range (same flag CI's version matrix uses):

```
mvn -pl flowable-test-example -am test -Dflowable.version=7.0.0
```

To run the app standalone (needs a real Kafka broker reachable at `localhost:9092`; H2 is used
automatically, no Postgres needed):

```
mvn -pl flowable-test-example spring-boot:run
```

```
curl -X POST localhost:8080/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-001","totalAmount":49.99}'
```

## Note on test isolation

The embedded Kafka broker the starter starts is a JVM-wide singleton (at most one per Surefire
fork). `OrderAutoApprovedFlowTest` uses the default WireMock stub while
`OrderManualReviewFlowTest`/`PaymentCallbackEventRegistryTest` both override it via
`@MockExternalService` — different configurations mean different, simultaneously-cached Spring test
contexts, each with its own Flowable engine. Two such engines would otherwise register as competing
members of the same Kafka consumer group against the one shared broker. This module's `pom.xml`
sets `<reuseForks>false</reuseForks>` so each test class gets its own JVM (and therefore its own
embedded broker) instead.
