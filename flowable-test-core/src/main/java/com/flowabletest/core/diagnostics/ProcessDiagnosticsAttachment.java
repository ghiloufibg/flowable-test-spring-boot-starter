package com.flowabletest.core.diagnostics;

/**
 * A {@link RuntimeException} carrying a formatted diagnostics report, attached as a suppressed
 * exception on the original test failure by {@link FlowableProcessDiagnosticsExtension} and by
 * {@code ProcessTestHarness}. Constructed with {@code writableStackTrace = false}, so it never
 * generates a stack trace of its own -- only its message, the diagnostics text, is printed -- and
 * it appears as a clean addendum wherever the original throwable's stack trace is already rendered
 * (IDE, console, Surefire reports), with no extra frames of noise.
 */
public final class ProcessDiagnosticsAttachment extends RuntimeException {

  public ProcessDiagnosticsAttachment(String diagnostics) {
    super(diagnostics, null, false, false);
  }
}
