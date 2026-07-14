package com.flowabletest.core.diagnostics;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;

/**
 * Records every process instance ID started since the last {@link #reset()}, so test-failure
 * diagnostics can be scoped to exactly the process instances the failing test touched -- correct
 * even when the underlying engine/database is shared across test classes (see {@code
 * claudedocs/bpmn-failure-diagnostics-design.md} for why a global "list all process instances at
 * failure time" query would be wrong under this starter's shared-DB test modes).
 *
 * <p>Registered on the Flowable engine's event dispatcher for {@code PROCESS_STARTED} only, so
 * {@link #onEvent} may run on a job-executor thread (async continuations, call activities) rather
 * than the test thread -- the backing set is synchronized accordingly.
 */
public final class ProcessInstanceTracker implements FlowableEventListener {

  private final Set<String> processInstanceIds = Collections.synchronizedSet(new LinkedHashSet<>());

  @Override
  public void onEvent(FlowableEvent event) {
    if (event instanceof FlowableEngineEvent engineEvent
        && engineEvent.getProcessInstanceId() != null) {
      processInstanceIds.add(engineEvent.getProcessInstanceId());
    }
  }

  @Override
  public boolean isFailOnException() {
    // A bug in this listener must never fail the process being tested, let alone the test itself.
    return false;
  }

  @Override
  public boolean isFireOnTransactionLifecycleEvent() {
    return false;
  }

  @Override
  public String getOnTransaction() {
    return null;
  }

  /** Clears tracked state; called once per test method, before it runs. */
  public void reset() {
    processInstanceIds.clear();
  }

  /** Every process instance ID started since the last {@link #reset()}, in start order. */
  public List<String> trackedProcessInstanceIds() {
    synchronized (processInstanceIds) {
      return List.copyOf(processInstanceIds);
    }
  }
}
