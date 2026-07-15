package com.flowabletest.debugui;

/**
 * Escapes arbitrary process data (variable names/values, activity names) for safe HTML embedding.
 */
final class Html {

  private Html() {}

  static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
