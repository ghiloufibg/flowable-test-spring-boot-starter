package com.flowabletest.core.diagnostics;

/**
 * Carries a formatted diagnostics report as a suppressed exception on the original test failure.
 * {@code writableStackTrace = false} means it never generates a stack trace of its own -- only its
 * message (the diagnostics text) is printed -- so it appears as a clean addendum wherever the
 * original throwable's stack trace is already rendered (IDE, console, Surefire reports), with no
 * extra frames of noise.
 */
public final class ProcessDiagnosticsAttachment extends RuntimeException {

  public ProcessDiagnosticsAttachment(String diagnostics) {
    super(diagnostics, null, false, false);
  }
}
