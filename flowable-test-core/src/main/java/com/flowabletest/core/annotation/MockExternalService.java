package com.flowabletest.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redirects a test class to an alternate WireMock stub folder for one named service, for example to
 * simulate a gateway timeout only for a failure-path test. Repeatable across multiple services;
 * repeated occurrences are collected into {@link MockExternalServices}.
 *
 * <p>Without this annotation, the starter auto-discovers plain WireMock mapping JSON files under
 * {@code classpath:httpmocks/<service-name>/mappings/*.json}, starts an in-process WireMock server
 * per subfolder, and injects {@code <service-name>.base-url} into the environment. This annotation
 * only changes which folder is used for {@link #name()}; it never carries stub content itself, so
 * the same WireMock JSON file layout applies either way. Processed by a dedicated {@code
 * ContextCustomizer} rather than the environment post-processor that handles the classpath scan,
 * since per-class annotations aren't visible at that stage.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(MockExternalServices.class)
public @interface MockExternalService {

  /**
   * Service name; also the default folder name and the property prefix ({@code <name>.base-url}).
   */
  String name();

  /**
   * Classpath location of the alternate stub folder (containing its own {@code mappings/} and
   * optional {@code __files/}). Empty means "use the convention default", {@code
   * classpath:httpmocks/<name>}.
   */
  String stubs() default "";
}
