package com.flowabletest.example.api;

import com.flowabletest.example.domain.Order;
import com.flowabletest.example.service.OrderProcessService;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderProcessService orderProcessService;

  public OrderController(OrderProcessService orderProcessService) {
    this.orderProcessService = orderProcessService;
  }

  @PostMapping
  public Order createOrder(@RequestBody CreateOrderRequest request) {
    return orderProcessService.startOrderProcess(request.customerId(), request.totalAmount());
  }

  public record CreateOrderRequest(String customerId, BigDecimal totalAmount) {}
}
