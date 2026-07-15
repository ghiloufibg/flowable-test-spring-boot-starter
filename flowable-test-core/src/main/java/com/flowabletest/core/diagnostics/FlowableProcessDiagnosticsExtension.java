package com.flowabletest.core.diagnostics;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * JUnit 5 extension that resets {@link ProcessInstanceTracker} state before each test and, when a
 * test fails, attaches a {@link ProcessDiagnosticsCollector} snapshot of every process instance the
 * test touched to the failure as a suppressed {@link ProcessDiagnosticsAttachment}. Triggers on any
 * thrown failure, not just failures raised by this starter's own assertions or harness.
 *
 * <p>Wired automatically by {@code @FlowableProcessTest}; a consumer never registers this extension
 * directly. Both beans are looked up defensively ({@code getIfAvailable()}, never a hard lookup),
 * so a disabled {@code flowable.test.diagnostics.enabled=false}, or no Spring context yet, degrades
 * to a silent no-op. A failure while collecting diagnostics is logged and swallowed, never allowed
 * to replace or mask the original test failure.
 */
public final class FlowableProcessDiagnosticsExtension implements BeforeEachCallback, TestWatcher {

  private static final Logger log =
      LoggerFactory.getLogger(FlowableProcessDiagnosticsExtension.class);

  @Override
  public void beforeEach(ExtensionContext context) {
    tracker(context).ifPresent(ProcessInstanceTracker::reset);
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    try {
      final Optional<ProcessInstanceTracker> tracker = tracker(context);
      final Optional<ProcessDiagnosticsCollector> collector = collector(context);
      if (tracker.isEmpty() || collector.isEmpty()) {
        return;
      }
      final List<ProcessDiagnosticsReport> reports =
          tracker.get().trackedProcessInstanceIds().stream().map(collector.get()::collect).toList();
      cause.addSuppressed(
          new ProcessDiagnosticsAttachment(
              ProcessDiagnosticsFormatter.format(
                  reports, tracker.get().omittedProcessInstanceCount())));
    } catch (final RuntimeException diagnosticsFailure) {
      log.warn(
          "Failed to collect Flowable process diagnostics for failed test", diagnosticsFailure);
    }
  }

  private Optional<ProcessInstanceTracker> tracker(ExtensionContext context) {
    return applicationContext(context)
        .map(ctx -> ctx.getBeanProvider(ProcessInstanceTracker.class).getIfAvailable());
  }

  private Optional<ProcessDiagnosticsCollector> collector(ExtensionContext context) {
    return applicationContext(context)
        .map(ctx -> ctx.getBeanProvider(ProcessDiagnosticsCollector.class).getIfAvailable());
  }

  private Optional<ApplicationContext> applicationContext(ExtensionContext context) {
    try {
      return Optional.of(SpringExtension.getApplicationContext(context));
    } catch (final IllegalStateException noSpringContextYet) {
      return Optional.empty();
    }
  }
}
