package com.flowabletest.debugui;

import com.flowabletest.core.diagnostics.ProcessInstanceTracker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.List;

/**
 * {@code GET /} -- every process instance {@link ProcessInstanceTracker} has tracked since the
 * currently-running test method's last reset. {@link ProcessInstanceTracker} is reset before every
 * test method, so this list never spans more than the test method currently executing, even though
 * the server itself lives for the whole Spring context's lifetime.
 */
final class InstanceListHandler implements HttpHandler {

  private final ProcessInstanceTracker processInstanceTracker;

  InstanceListHandler(ProcessInstanceTracker processInstanceTracker) {
    this.processInstanceTracker = processInstanceTracker;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    final List<String> processInstanceIds = processInstanceTracker.trackedProcessInstanceIds();
    final StringBuilder items = new StringBuilder();
    if (processInstanceIds.isEmpty()) {
      items.append("<li>(no process instances tracked for the current test method yet)</li>");
    } else {
      for (final String processInstanceId : processInstanceIds) {
        final String escapedId = Html.escape(processInstanceId);
        items
            .append("<li><a href=\"/instances/")
            .append(escapedId)
            .append("\">")
            .append(escapedId)
            .append("</a></li>");
      }
    }
    final String html =
        """
        <!doctype html>
        <html>
        <head><title>Flowable Test debug UI</title></head>
        <body>
        <h1>Process instances (current test method)</h1>
        <ul>%s</ul>
        </body>
        </html>
        """
            .formatted(items);
    HttpResponses.sendHtml(exchange, 200, html);
  }
}
