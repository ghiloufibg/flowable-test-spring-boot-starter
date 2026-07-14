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
 * even when the underlying engine/database is shared across test classes, where a global "list all
 * process instances at failure time" query would sweep up unrelated instances from other tests.
 * Stops tracking new IDs once {@code maxTrackedProcessInstances} is reached, so a test that starts
 * an unusually large number of instances can't turn a single failure into an unbounded number of
 * diagnostics queries; {@link #omittedProcessInstanceCount()} reports how many were left out.
 *
 * <p>Registered on the Flowable engine's event dispatcher for {@code PROCESS_STARTED} only, so
 * {@link #onEvent} may run on a job-executor thread (async continuations, call activities) rather
 * than the test thread -- the backing set is synchronized accordingly.
 */
public final class ProcessInstanceTracker implements FlowableEventListener {

  private final int maxTrackedProcessInstances;
  private final Set<String> processInstanceIds = Collections.synchronizedSet(new LinkedHashSet<>());
  private int totalObservedCount = 0;

  public ProcessInstanceTracker(int maxTrackedProcessInstances) {
    this.maxTrackedProcessInstances = maxTrackedProcessInstances;
  }

  @Override
  public void onEvent(FlowableEvent event) {
    if (event instanceof FlowableEngineEvent engineEvent
        && engineEvent.getProcessInstanceId() != null) {
      final String processInstanceId = engineEvent.getProcessInstanceId();
      synchronized (processInstanceIds) {
        if (processInstanceIds.contains(processInstanceId)) {
          return;
        }
        totalObservedCount++;
        if (processInstanceIds.size() < maxTrackedProcessInstances) {
          processInstanceIds.add(processInstanceId);
        }
      }
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
    synchronized (processInstanceIds) {
      processInstanceIds.clear();
      totalObservedCount = 0;
    }
  }

  /** Every process instance ID started since the last {@link #reset()}, in start order. */
  public List<String> trackedProcessInstanceIds() {
    synchronized (processInstanceIds) {
      return List.copyOf(processInstanceIds);
    }
  }

  /**
   * How many process instances starting since the last {@link #reset()} were observed but not
   * tracked because {@code maxTrackedProcessInstances} was already reached.
   */
  public int omittedProcessInstanceCount() {
    synchronized (processInstanceIds) {
      return totalObservedCount - processInstanceIds.size();
    }
  }
}
