package com.flowabletest.debugui;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Shared response-writing plumbing for this module's {@code HttpHandler}s. */
final class HttpResponses {

  private HttpResponses() {}

  static void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
    send(exchange, statusCode, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
  }

  static void sendPlainText(HttpExchange exchange, int statusCode, String text) throws IOException {
    send(exchange, statusCode, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
  }

  static void sendPng(HttpExchange exchange, byte[] png) throws IOException {
    send(exchange, 200, "image/png", png);
  }

  static void sendXml(HttpExchange exchange, int statusCode, byte[] xml) throws IOException {
    send(exchange, statusCode, "application/xml; charset=utf-8", xml);
  }

  private static void send(HttpExchange exchange, int statusCode, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream responseBody = exchange.getResponseBody()) {
      responseBody.write(body);
    }
  }
}
