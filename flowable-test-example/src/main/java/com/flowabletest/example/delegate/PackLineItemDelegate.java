package com.flowabletest.example.delegate;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Runs once per line item as a multi-instance activity in {@code
 * shipment-orchestration.bpmn20.xml}'s "Prepare Shipment" subProcess. Sets a local variable (scoped
 * to this multi-instance execution only, never the shared process instance) so concurrent instances
 * never race on the same variable.
 */
@Component("packLineItemDelegate")
public class PackLineItemDelegate implements JavaDelegate {

  @Override
  public void execute(DelegateExecution execution) {
    execution.setVariableLocal("packed", true);
  }
}
