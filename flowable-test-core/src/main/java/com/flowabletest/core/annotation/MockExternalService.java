package com.flowabletest.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Escape hatch for the rare case where convention isn't enough: a specific test class needs
 * different HTTP stubs than the shared default folder (e.g. simulating a gateway timeout only for a
 * failure-path test).
 *
 * <p>The default, zero-annotation path is: drop plain WireMock mapping JSON files under {@code
 * classpath:httpmocks/<service-name>/mappings/*.json} and the starter auto-discovers them, starts
 * an in-process WireMock server per subfolder, and injects {@code <service-name>.base-url} into the
 * environment -- no annotation required. This annotation only redirects a test to an alternate stub
 * folder; it never carries stub content itself, so the "declarative JSON file" contract stays
 * identical either way.
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
