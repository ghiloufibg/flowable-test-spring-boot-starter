# How Per-Class Conflicting WireMock Stubs Are Handled

Date: 2026-07-13
Question answered: WireMock servers are shared JVM-wide (`EmbeddedFlowableHttpMockSupport`) — if test
class A needs endpoint `K` to return 200 and test class B needs the same endpoint to return 404, how is
that reconciled without one class clobbering the other?

## The sharing key isn't the service name alone

`EmbeddedFlowableHttpMockSupport.SERVERS` is keyed by **`name + "|" + classpathLocation`**, not by name
alone (`EmbeddedFlowableHttpMockSupport.java:20,44-46`):

```java
static WireMockServer startIfNeeded(String name, String classpathLocation) {
  return SERVERS.computeIfAbsent(key(name, classpathLocation), ...);
}
```

"Shared" means two test classes requesting the *same* `(name, location)` pair get the same server
instance. It does not mean every consumer of a service named e.g. `payment-service` is forced onto one
server with one fixed set of responses.

## Resolution mechanism: `@MockExternalService(stubs = ...)`

- **Class A** (no annotation, zero-config default): resolves to `httpmocks/payment-service` → the
  shared default server, endpoint `K` → 200.
- **Class B**: `@MockExternalService(name = "payment-service", stubs = "classpath:httpmocks-404/payment-service")`
  → different classpath location → different cache key → `startIfNeeded` starts a **separate** WireMock
  server on a different port, loaded from a different mappings folder where `K` → 404. No interference
  with class A's server.

```
src/test/resources/httpmocks/payment-service/mappings/k.json          → 200
src/test/resources/httpmocks-404/payment-service/mappings/k.json      → 404
```
```java
@FlowableProcessTest(classes = SampleFlowableApplication.class)
class ClassA { /* no annotation — uses the default 200 mapping */ }

@FlowableProcessTest(classes = SampleFlowableApplication.class)
@MockExternalService(name = "payment-service", stubs = "classpath:httpmocks-404/payment-service")
class ClassB { /* gets its own server, 404 mapping */ }
```

This is exactly what the existing `MockExternalServiceOverrideTest.java` already proves: the default
folder returns `"hi from demo-service"`, the override folder (`httpmocks-alt/demo-service`) returns
`"hi from ALT demo-service"` — same service name, deliberately different content, no conflict, because
they're different server instances.

## Why this is safe, not just usually-safe

`MockExternalServiceContextCustomizer` (`MockExternalServiceContextCustomizerFactory.java:22-25`) makes
the `@MockExternalService` override set part of Spring's `MergedContextConfiguration` cache key (records
get value-based `equals`/`hashCode` for free). Without that, Spring's test-context cache could judge
class A and class B "identical config" and **share one context** — meaning whichever `<name>.base-url`
property got injected first would silently apply to both. The customizer forces separate contexts
precisely because their base-url values need to differ.

## What happens if you *don't* use the override

If both classes rely on the same default `(name, location)` convention and you try to get different
behavior "depending on which test runs" by editing the shared JSON file, that doesn't work.
`computeIfAbsent` starts the server once per `(name, location)` key for the life of the JVM — whichever
mappings are on disk at first-start apply to every consumer of that key, permanently, for that test run.
There is no support for swapping mappings based on which test class is currently active.

## `HttpStubConfigurer` is the wrong tool for this specific need

`HttpStubConfigurer` (`flowable-test-core/.../http/HttpStubConfigurer.java`) registers a stub
imperatively (`server.stubFor(...)`) for cases a static JSON file can't express (e.g. echoing a
computed value). It operates on the **same shared server object** — if class B's configurer mutates a
stub for endpoint `K`, that mutation lives on the shared server and can leak to class A too
(order-dependent, genuinely risky). It's designed for *adding* one dynamic stub on top of the
declarative ones, not for two classes disagreeing about the same endpoint. For genuinely conflicting
expectations, `@MockExternalService(stubs = ...)` — a separate server — is the correct mechanism.
