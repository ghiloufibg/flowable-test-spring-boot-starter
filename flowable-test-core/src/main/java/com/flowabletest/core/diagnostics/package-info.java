/**
 * Automatic BPMN failure diagnostics: turns a process instance ID into a snapshot of current
 * activity, variables, activity trail, pending tasks, and dead-letter job failures ({@link
 * com.flowabletest.core.diagnostics.ProcessDiagnosticsCollector}), renders it for human reading
 * ({@link com.flowabletest.core.diagnostics.ProcessDiagnosticsFormatter}), and attaches it as a
 * suppressed exception ({@link com.flowabletest.core.diagnostics.ProcessDiagnosticsAttachment}) on
 * any test failure -- whether raised by this starter's own assertions/harness or by arbitrary
 * test/delegate code, via {@link
 * com.flowabletest.core.diagnostics.FlowableProcessDiagnosticsExtension} (wired automatically by
 * {@code @FlowableProcessTest}). {@link com.flowabletest.core.diagnostics.ProcessInstanceTracker}
 * scopes each snapshot to exactly the process instances the failing test touched, even on a shared
 * engine/database.
 */
package com.flowabletest.core.diagnostics;
