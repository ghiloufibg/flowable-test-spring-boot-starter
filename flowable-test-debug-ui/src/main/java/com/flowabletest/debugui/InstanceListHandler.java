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
    HttpResponses.sendHtml(exchange, 200, renderPage(processInstanceIds));
  }

  private static String renderPage(List<String> processInstanceIds) {
    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Flowable Test debug UI</title>
          %s
        </head>
        <body>
        %s
        <main class="flw-main">
          <div class="flw-page-header">
            <h1>Process instances</h1>
            <p class="flw-subtitle">Tracked for the currently running test method.</p>
          </div>
          <div class="flw-toolbar">
            <input id="flw-filter" type="search" placeholder="Filter by instance ID&hellip;"
                   oninput="flwFilterInstances(this.value)" %s>
          </div>
          <div class="flw-card">
            %s
          </div>
        </main>
        <script>
          function flwFilterInstances(query) {
            const needle = query.trim().toLowerCase();
            document.querySelectorAll('#flw-instance-list li[data-id]').forEach(function (row) {
              row.style.display = row.dataset.id.toLowerCase().includes(needle) ? '' : 'none';
            });
          }
        </script>
        </body>
        </html>
        """
        .formatted(
            Layout.STYLE,
            Layout.topBar(processInstanceIds.size() + " instance(s) tracked"),
            processInstanceIds.isEmpty() ? "disabled" : "",
            renderList(processInstanceIds));
  }

  private static String renderList(List<String> processInstanceIds) {
    if (processInstanceIds.isEmpty()) {
      return "<p class=\"flw-empty-state\">No process instances tracked for the current test "
          + "method yet.</p>";
    }
    final StringBuilder items = new StringBuilder("<ul class=\"flw-instance-list\" id=\"flw-instance-list\">");
    for (final String processInstanceId : processInstanceIds) {
      final String escapedId = Html.escape(processInstanceId);
      items
          .append("<li data-id=\"")
          .append(escapedId)
          .append("\" class=\"flw-instance-row\"><a href=\"/instances/")
          .append(escapedId)
          .append("\" class=\"flw-instance-link\"><span class=\"flw-instance-icon\">&#9654;</span>"
              + "<span class=\"flw-mono\">")
          .append(escapedId)
          .append("</span></a></li>");
    }
    return items.append("</ul>").toString();
  }
}
