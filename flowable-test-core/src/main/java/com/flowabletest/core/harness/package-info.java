/**
 * {@link com.flowabletest.core.harness.ProcessTestHarness} -- the generic BPMN process-testing
 * primitives (task completion, wait-state polling) that otherwise get hand-duplicated in every
 * Flowable test class. Waits are driven by the engine's own event dispatcher rather than a fixed
 * sleep interval: a {@code FlowableEventListener} registered for the task/activity/process/job
 * event types wakes a pending wait the moment the engine actually advances, and fails fast, with
 * diagnostics attached, the moment a dead-letter job appears for the awaited process instance
 * instead of waiting out the full timeout.
 */
package com.flowabletest.core.harness;
