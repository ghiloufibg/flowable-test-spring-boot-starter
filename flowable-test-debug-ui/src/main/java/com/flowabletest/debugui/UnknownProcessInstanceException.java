package com.flowabletest.debugui;

/** A process instance ID that resolves in neither runtime nor history state. */
final class UnknownProcessInstanceException extends RuntimeException {

  UnknownProcessInstanceException(String processInstanceId) {
    super("No process instance found for ID <" + processInstanceId + ">");
  }
}
