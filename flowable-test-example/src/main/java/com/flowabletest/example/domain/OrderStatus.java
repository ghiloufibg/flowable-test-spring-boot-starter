package com.flowabletest.example.domain;

/**
 * CREATED -> PENDING_MANAGER_APPROVAL (only when the fraud check declines) -> ACCEPTED/REJECTED.
 * PAID/PAYMENT_FAILED are applied later, out of band, by the payment-callback event subprocess.
 */
public enum OrderStatus {
  CREATED,
  PENDING_MANAGER_APPROVAL,
  ACCEPTED,
  REJECTED,
  PAID,
  PAYMENT_FAILED
}
