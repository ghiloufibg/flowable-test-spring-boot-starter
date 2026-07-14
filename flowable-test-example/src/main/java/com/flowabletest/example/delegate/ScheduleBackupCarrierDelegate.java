package com.flowabletest.example.delegate;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Runs in {@code carrier-dispatch.bpmn20.xml} once {@code carrierPickupTimeoutBoundary} fires
 * because nobody completed "Await Carrier Pickup" in time. Sets {@code carrierStatus} the same way
 * a manual task completion would, so the callActivity's {@code flowable:out} mapping back into
 * {@code shipment-orchestration.bpmn20.xml} behaves identically regardless of which path the child
 * process took.
 */
@Component("scheduleBackupCarrierDelegate")
public class ScheduleBackupCarrierDelegate implements JavaDelegate {

  @Override
  public void execute(DelegateExecution execution) {
    execution.setVariable("carrierStatus", "ESCALATED_BACKUP_CARRIER");
  }
}
