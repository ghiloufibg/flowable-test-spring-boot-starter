package com.flowabletest.autoconfigure.guard;

import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Fails fast with an actionable message if the consumer's actual runtime Flowable engine version
 * falls outside this starter's supported range, instead of letting a version mismatch surface later
 * as an obscure {@code NoSuchMethodError} mid-test.
 *
 * <p>Activates once a {@code ProcessEngine} bean exists and {@code RuntimeService} is on the
 * classpath. Runs {@code afterName} Flowable's own {@code ProcessEngineAutoConfiguration} but at
 * {@link AutoConfigureOrder}'s {@link Ordered#HIGHEST_PRECEDENCE HIGHEST_PRECEDENCE} among this
 * starter's own auto-configurations, so an unsupported engine is reported before any other
 * capability tries to use it. {@link #flowableCompatibilityGuard} returns an {@link
 * InitializingBean} that compares {@link ProcessEngine#VERSION} against {@link
 * FlowableVersions#isSupported}.
 */
@AutoConfiguration(afterName = "org.flowable.spring.boot.ProcessEngineAutoConfiguration")
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
@ConditionalOnBean(ProcessEngine.class)
public class FlowableCompatibilityGuardAutoConfiguration {

  // 7.x only. Track this alongside the starter's own release line.
  private static final String SUPPORTED_MIN_INCLUSIVE = "7.0.0";
  private static final String SUPPORTED_MAX_EXCLUSIVE = "8.0.0";
  private static final String STARTER_VERSION = resolveStarterVersion();

  @Bean
  InitializingBean flowableCompatibilityGuard(ProcessEngine processEngine) {
    return () -> {
      final String detected = resolveRuntimeEngineVersion();
      if (!FlowableVersions.isSupported(
          detected, SUPPORTED_MIN_INCLUSIVE, SUPPORTED_MAX_EXCLUSIVE)) {
        throw new IllegalStateException(
            """
            flowable-test-spring-boot-starter %s supports Flowable [%s, %s), but the consumer \
            project resolved Flowable %s. Align your org.flowable:flowable-spring-boot-starter \
            version, or use a starter release matching your Flowable version -- see the \
            compatibility matrix in the README."""
                .formatted(
                    STARTER_VERSION, SUPPORTED_MIN_INCLUSIVE, SUPPORTED_MAX_EXCLUSIVE, detected));
      }
    };
  }

  /**
   * Reads {@link ProcessEngine#VERSION} reflectively instead of by direct field access. {@code
   * VERSION} is a compile-time constant ({@code public static final String}), so a direct reference
   * (e.g. plain {@code ProcessEngine.VERSION}) gets inlined into this class's own compiled bytecode
   * at <em>this module's own build time</em> -- always the Flowable version this starter itself was
   * built against, never whatever {@code flowable-engine} version the consumer's own dependency
   * management actually resolves at runtime, since {@code provided} scope means that version is
   * never on this module's compile classpath. Reflection reads the field's live value off the
   * {@code ProcessEngine} class as loaded from the consumer's own runtime classpath, bypassing that
   * compile-time inlining entirely.
   */
  static String resolveRuntimeEngineVersion() {
    try {
      return (String) ProcessEngine.class.getField("VERSION").get(null);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to read ProcessEngine.VERSION reflectively", e);
    }
  }

  /**
   * Reads the starter's own version from the jar manifest's {@code Implementation-Version}
   * (populated by Maven from {@code project.version} at package time), rather than duplicating it
   * as a literal that would silently drift from the POM on every release. Falls back to a
   * descriptive placeholder when run from unpackaged classes (e.g. inside this module's own test
   * suite, which runs against {@code target/classes}, not the built jar, and so has no manifest).
   */
  private static String resolveStarterVersion() {
    final String version =
        FlowableCompatibilityGuardAutoConfiguration.class.getPackage().getImplementationVersion();
    return version != null ? version : "development (unpackaged build)";
  }
}
