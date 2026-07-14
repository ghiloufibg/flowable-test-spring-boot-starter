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
 * <p>Runs first among this starter's auto-configurations so an unsupported engine is reported
 * before any other capability tries to use it.
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
      final String detected = ProcessEngine.VERSION;
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
