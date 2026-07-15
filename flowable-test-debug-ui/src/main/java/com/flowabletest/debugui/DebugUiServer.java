package com.flowabletest.debugui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * A read-only, localhost-only HTTP server for the BPMN debug UI. Bound explicitly to {@link
 * InetAddress#getLoopbackAddress()} -- never the bare-port constructor, which binds every network
 * interface -- so this is never reachable off the machine it runs on. Started/stopped with the
 * owning {@code ApplicationContext} via {@link SmartLifecycle}.
 */
final class DebugUiServer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(DebugUiServer.class);
  private static final String INSTANCES_PATH_PREFIX = "/instances/";
  private static final String DIAGRAM_PATH_SUFFIX = "/diagram.png";

  private final DebugUiProperties properties;
  private final InstanceListHandler instanceListHandler;
  private final InstanceDetailHandler instanceDetailHandler;
  private final DiagramImageHandler diagramImageHandler;
  private final String applicationContextId;
  private HttpServer httpServer;

  DebugUiServer(
      DebugUiProperties properties,
      InstanceListHandler instanceListHandler,
      InstanceDetailHandler instanceDetailHandler,
      DiagramImageHandler diagramImageHandler,
      String applicationContextId) {
    this.properties = properties;
    this.instanceListHandler = instanceListHandler;
    this.instanceDetailHandler = instanceDetailHandler;
    this.diagramImageHandler = diagramImageHandler;
    this.applicationContextId = applicationContextId;
  }

  @Override
  public void start() {
    try {
      httpServer =
          HttpServer.create(
              new InetSocketAddress(InetAddress.getLoopbackAddress(), properties.port()), 0);
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to bind the Flowable Test debug UI's HTTP server", e);
    }
    httpServer.createContext("/", instanceListHandler);
    httpServer.createContext(
        INSTANCES_PATH_PREFIX,
        exchange -> {
          if (exchange.getRequestURI().getPath().endsWith(DIAGRAM_PATH_SUFFIX)) {
            diagramImageHandler.handle(exchange);
          } else {
            instanceDetailHandler.handle(exchange);
          }
        });
    httpServer.start();
    log.info(
        "Flowable Test debug UI at http://localhost:{} (context {})", port(), applicationContextId);
  }

  @Override
  public void stop() {
    if (httpServer != null) {
      httpServer.stop(0);
      httpServer = null;
    }
  }

  @Override
  public boolean isRunning() {
    return httpServer != null;
  }

  /** The actual bound port -- only meaningful once {@link #isRunning()}, resolves {@code 0}. */
  int port() {
    return httpServer.getAddress().getPort();
  }
}
