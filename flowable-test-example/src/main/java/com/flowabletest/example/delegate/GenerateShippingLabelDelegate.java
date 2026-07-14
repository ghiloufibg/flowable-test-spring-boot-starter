package com.flowabletest.example.delegate;

import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Runs after every line item is packed in {@code shipment-orchestration.bpmn20.xml}'s "Prepare
 * Shipment" subProcess. Sets {@code trackingNumber} as a process-instance-scoped variable so it
 * survives the subProcess boundary and is visible to the callActivity's {@code flowable:in} mapping
 * into {@code carrier-dispatch.bpmn20.xml}.
 */
@Component("generateShippingLabelDelegate")
public class GenerateShippingLabelDelegate implements JavaDelegate {

  @Override
  public void execute(DelegateExecution execution) {
    execution.setVariable("trackingNumber", "TRK-" + UUID.randomUUID());
  }
}
