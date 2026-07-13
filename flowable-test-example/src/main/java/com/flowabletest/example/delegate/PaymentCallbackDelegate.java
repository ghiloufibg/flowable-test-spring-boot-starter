package com.flowabletest.example.delegate;

import com.flowabletest.example.domain.OrderStatus;
import com.flowabletest.example.repository.OrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/**
 * Runs inside the "Payment Callback Received" event subprocess in {@code
 * order-processing.bpmn20.xml}. The subprocess's start event is a Flowable Event Registry event
 * correlated on {@code orderId} -- Flowable itself consumes the {@code payment-callbacks} Kafka
 * topic and routes matching messages into the correct running process instance; this delegate only
 * applies the resulting side effect to the {@code Order} row.
 */
@Component("paymentCallbackDelegate")
public class PaymentCallbackDelegate implements JavaDelegate {

  private final OrderRepository orderRepository;

  public PaymentCallbackDelegate(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  @Override
  public void execute(DelegateExecution execution) {
    final String orderId = (String) execution.getVariable("orderId");
    final String status = (String) execution.getVariable("externalPaymentStatus");
    final String confirmationId = (String) execution.getVariable("externalConfirmationId");

    orderRepository
        .findById(orderId)
        .ifPresent(
            order -> {
              order.setStatus(
                  "SUCCESS".equalsIgnoreCase(status)
                      ? OrderStatus.PAID
                      : OrderStatus.PAYMENT_FAILED);
              order.setPaymentConfirmationId(confirmationId);
              orderRepository.save(order);
            });
  }
}
