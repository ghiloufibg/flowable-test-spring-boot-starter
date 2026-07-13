package com.flowabletest.example.service;

import com.flowabletest.example.domain.Order;
import com.flowabletest.example.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Map;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entry point between the application layer and the Flowable engine for the order process. */
@Service
@Transactional
public class OrderProcessService {

  private static final String PROCESS_KEY = "orderProcessing";

  private final RuntimeService runtimeService;
  private final OrderRepository orderRepository;

  public OrderProcessService(RuntimeService runtimeService, OrderRepository orderRepository) {
    this.runtimeService = runtimeService;
    this.orderRepository = orderRepository;
  }

  /** Persists the order, then starts the BPMN process with its id as the business key. */
  public Order startOrderProcess(String customerId, BigDecimal totalAmount) {
    final Order order = new Order(customerId, totalAmount);
    orderRepository.save(order);

    final Map<String, Object> variables =
        Map.of(
            "orderId", order.getId(),
            "customerId", customerId,
            "totalAmount", totalAmount);

    final ProcessInstance processInstance =
        runtimeService.startProcessInstanceByKey(PROCESS_KEY, order.getId(), variables);

    order.setProcessInstanceId(processInstance.getId());
    orderRepository.save(order);
    return order;
  }
}
