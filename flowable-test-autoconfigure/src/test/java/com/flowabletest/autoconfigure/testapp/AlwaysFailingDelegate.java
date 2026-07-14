package com.flowabletest.autoconfigure.testapp;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

/**
 * Always throws -- used by {@code diagnostics-async-fail.bpmn20.xml} to prove {@code
 * ProcessDiagnosticsCollector} surfaces dead-letter job failures. Never actually executed by tests
 * that disable the async executor; they move the pending job to dead-letter status directly instead
 * of waiting on real retry backoff timing.
 */
public class AlwaysFailingDelegate implements JavaDelegate {

  @Override
  public void execute(DelegateExecution execution) {
    throw new IllegalStateException("AlwaysFailingDelegate always fails");
  }
}
