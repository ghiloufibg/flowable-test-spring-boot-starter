package com.flowabletest.example.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

  @Id private String id;

  @Column(nullable = false)
  private String customerId;

  @Column(nullable = false, precision = 15, scale = 2)
  private BigDecimal totalAmount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrderStatus status;

  private String processInstanceId;
  private Double fraudScore;
  private String paymentConfirmationId;

  protected Order() {
    /* JPA */
  }

  public Order(String customerId, BigDecimal totalAmount) {
    this.id = UUID.randomUUID().toString();
    this.customerId = customerId;
    this.totalAmount = totalAmount;
    this.status = OrderStatus.CREATED;
  }

  public String getId() {
    return id;
  }

  public String getCustomerId() {
    return customerId;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public Double getFraudScore() {
    return fraudScore;
  }

  public String getPaymentConfirmationId() {
    return paymentConfirmationId;
  }

  public void setStatus(OrderStatus status) {
    this.status = status;
  }

  public void setProcessInstanceId(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  public void setFraudScore(Double fraudScore) {
    this.fraudScore = fraudScore;
  }

  public void setPaymentConfirmationId(String paymentConfirmationId) {
    this.paymentConfirmationId = paymentConfirmationId;
  }
}
