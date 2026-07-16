package com.flowabletest.debugui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /static/{fileName}} -- serves classpath resources under {@code static/} (currently
 * only the vendored Alpine.js prototype build). {@code fileName} is rejected outright unless it
 * matches a plain {@code [A-Za-z0-9._-]+} filename with no path separators, so {@code ../}
 * traversal outside the {@code static/} classpath root is not a resolvable input in the first
 * place, rather than something checked after path resolution.
 *
 * <p>Every served file name embeds its exact vendored version (e.g. {@code alpine-3.15.12.min.js}),
 * so responses are cached aggressively and immutably -- a new version gets a new file name, never a
 * mutated response body under the same URL.
 */
final class StaticResourceHandler implements HttpHandler {

  private static final String PATH_PREFIX = "/static/";
  private static final String CLASSPATH_ROOT = "static/";

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    final String fileName =
        URI.create(exchange.getRequestURI().getPath()).getPath().substring(PATH_PREFIX.length());
    if (!fileName.matches("[A-Za-z0-9._-]+")) {
      HttpResponses.sendPlainText(exchange, 400, "Invalid static resource name <" + fileName + ">");
      return;
    }
    try (InputStream resource =
        getClass().getClassLoader().getResourceAsStream(CLASSPATH_ROOT + fileName)) {
      if (resource == null) {
        HttpResponses.sendPlainText(exchange, 404, "No static resource named <" + fileName + ">");
        return;
      }
      final byte[] body = resource.readAllBytes();
      exchange.getResponseHeaders().add("Content-Type", contentTypeFor(fileName));
      exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000, immutable");
      exchange.sendResponseHeaders(200, body.length);
      try (var responseBody = exchange.getResponseBody()) {
        responseBody.write(body);
      }
    }
  }

  private static String contentTypeFor(String fileName) {
    if (fileName.endsWith(".js")) {
      return "application/javascript; charset=" + StandardCharsets.UTF_8.name();
    }
    return "text/plain; charset=" + StandardCharsets.UTF_8.name();
  }
}
