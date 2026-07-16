package com.flowabletest.core.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;

/**
 * A {@link FlowableEventListener} that records every process instance ID started since the last
 * {@link #reset()}, so test-failure diagnostics can be scoped to exactly the process instances the
 * failing test touched -- correct even when the underlying engine and database are shared across
 * test classes, where a global "list every process instance at failure time" query would sweep up
 * unrelated instances from other tests. Stops tracking new IDs once {@code
 * maxTrackedProcessInstances} is reached, so a test that starts an unusually large number of
 * instances can't turn a single failure into an unbounded number of diagnostics queries; {@link
 * #omittedProcessInstanceCount()} reports how many were left out.
 *
 * <p>Registered on the Flowable engine's event dispatcher for {@code PROCESS_STARTED} only, so
 * {@link #onEvent} may run on a job-executor thread (async continuations, call activities) rather
 * than the test thread -- the backing set is synchronized accordingly. Reset before each test and
 * consulted on failure by {@link FlowableProcessDiagnosticsExtension}.
 *
 * <p>Also records which test started each instance, via {@link #beginTestOrigin}/{@link
 * #endTestOrigin} -- a static {@link ThreadLocal} set by {@link
 * FlowableProcessDiagnosticsExtension} around each test method, read here at the moment {@link
 * #onEvent} fires. Only reliable for a synchronous, same-thread start: an instance started from an
 * async continuation or a call activity (running on a job-executor thread per the paragraph above)
 * will simply have no recorded origin, the same "may run on a different thread" limitation {@link
 * #onEvent} already has.
 */
public final class ProcessInstanceTracker implements FlowableEventListener {

  private static final ThreadLocal<String> CURRENT_TEST_ORIGIN = new ThreadLocal<>();

  private final int maxTrackedProcessInstances;
  private final Set<String> processInstanceIds = Collections.synchronizedSet(new LinkedHashSet<>());
  private final Map<String, String> processInstanceTestOrigins =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private int totalObservedCount = 0;

  public ProcessInstanceTracker(int maxTrackedProcessInstances) {
    this.maxTrackedProcessInstances = maxTrackedProcessInstances;
  }

  /** Called once per test method, before it runs; paired with {@link #endTestOrigin}. */
  static void beginTestOrigin(String testOrigin) {
    CURRENT_TEST_ORIGIN.set(testOrigin);
  }

  /** Called once per test method, after it runs, regardless of outcome. */
  static void endTestOrigin() {
    CURRENT_TEST_ORIGIN.remove();
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
          final String testOrigin = CURRENT_TEST_ORIGIN.get();
          if (testOrigin != null) {
            processInstanceTestOrigins.put(processInstanceId, testOrigin);
          }
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
      processInstanceTestOrigins.clear();
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
   * The test class and method (formatted {@code SimpleClassName.methodName}) that started {@code
   * processInstanceId}, or {@code null} if it wasn't started synchronously on a test thread with a
   * recorded origin -- see the class Javadoc's async-thread caveat.
   */
  public String testOriginFor(String processInstanceId) {
    return processInstanceTestOrigins.get(processInstanceId);
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
