package com.flowabletest.autoconfigure.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts (at most once per name+location combination, per JVM) the in-process WireMock servers
 * discovered by {@link FlowableTestHttpStubEnvironmentPostProcessor}, and tracks how many Spring
 * {@code ApplicationContext}s currently reference each one so a server can be stopped and its port
 * freed once the last referencing context closes, instead of living for the whole JVM. Same
 * rationale as {@code EmbeddedFlowableKafkaSupport}: this has to run before an {@code
 * ApplicationContext} exists, so it can't be a Spring bean itself.
 *
 * <p>Keyed by {@code name + "|" + classpathLocation} rather than name alone, so a test overriding a
 * service's stub folder via {@code @MockExternalService(stubs = ...)} gets its own server rather
 * than reusing one already loaded with different mappings.
 *
 * <p>{@link #ensureStarted(String, String)} only guarantees a server is running -- it has no
 * refcount side effect, since at the point both the environment post-processor and the context
 * customizer call it (pre-refresh, during context preparation), it isn't yet known whether a
 * default-folder start will be superseded by a later {@code @MockExternalService} override in the
 * same context. Only {@link #retain(String, String)}, called once per context for that context's
 * final override-applied service map, increments the refcount; {@link #release(String, String)}
 * (called from a {@code ContextClosedEvent} listener for that same map) decrements it and stops the
 * server at zero. All three methods, plus server creation, run under a single lock: contention is
 * irrelevant here since these only fire at context start/close, and a single lock rules out the
 * class of race where a concurrent {@code retain} could observe a server this thread is in the
 * middle of stopping.
 */
final class EmbeddedFlowableHttpMockSupport {

  private static final Object LOCK = new Object();
  private static final Map<String, WireMockServer> SERVERS = new ConcurrentHashMap<>();
  private static final Map<String, AtomicInteger> REF_COUNTS = new ConcurrentHashMap<>();
  private static final Map<String, Thread> SHUTDOWN_HOOKS = new ConcurrentHashMap<>();
  private static final Map<String, AtomicBoolean> STOPPED = new ConcurrentHashMap<>();

  private EmbeddedFlowableHttpMockSupport() {}

  static WireMockServer ensureStarted(String name, String classpathLocation) {
    synchronized (LOCK) {
      return SERVERS.computeIfAbsent(
          key(name, classpathLocation), key -> startNewServer(name, classpathLocation, key));
    }
  }

  static WireMockServer retain(String name, String classpathLocation) {
    final String key = key(name, classpathLocation);
    synchronized (LOCK) {
      final WireMockServer server =
          SERVERS.computeIfAbsent(key, k -> startNewServer(name, classpathLocation, k));
      REF_COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      return server;
    }
  }

  static void release(String name, String classpathLocation) {
    final String key = key(name, classpathLocation);
    synchronized (LOCK) {
      final AtomicInteger refCount = REF_COUNTS.get(key);
      if (refCount == null || refCount.decrementAndGet() > 0) {
        return;
      }
      REF_COUNTS.remove(key);
      final WireMockServer server = SERVERS.remove(key);
      final AtomicBoolean stopped = STOPPED.remove(key);
      if (server != null && stopped != null && stopped.compareAndSet(false, true)) {
        server.stop();
      }
      final Thread hook = SHUTDOWN_HOOKS.remove(key);
      if (hook != null) {
        try {
          Runtime.getRuntime().removeShutdownHook(hook);
        } catch (final IllegalStateException e) {
          // JVM already mid-shutdown; the hook itself will run and find `stopped` already true.
        }
      }
    }
  }

  static WireMockServer get(String name, String classpathLocation) {
    return SERVERS.get(key(name, classpathLocation));
  }

  /**
   * Test-only accessor: current refcount for a key, or 0 if untracked (never retained, or released
   * to zero).
   */
  static int refCount(String name, String classpathLocation) {
    final AtomicInteger refCount = REF_COUNTS.get(key(name, classpathLocation));
    return refCount == null ? 0 : refCount.get();
  }

  /** Test-only accessor: whether a server for this key is currently running. */
  static boolean isRunning(String name, String classpathLocation) {
    final WireMockServer server = SERVERS.get(key(name, classpathLocation));
    return server != null && server.isRunning();
  }

  private static WireMockServer startNewServer(String name, String classpathLocation, String key) {
    final WireMockServer server =
        new WireMockServer(
            WireMockConfiguration.options()
                .dynamicPort()
                .usingFilesUnderClasspath(classpathLocation));
    server.start();
    final AtomicBoolean stopped = new AtomicBoolean(false);
    STOPPED.put(key, stopped);
    final Thread hook =
        new Thread(
            () -> {
              if (stopped.compareAndSet(false, true)) {
                server.stop();
              }
            },
            "flowable-test-httpmock-shutdown-" + name);
    Runtime.getRuntime().addShutdownHook(hook);
    SHUTDOWN_HOOKS.put(key, hook);
    return server;
  }

  private static String key(String name, String classpathLocation) {
    return name + "|" + classpathLocation;
  }
}
