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
 * falls outside this starter's supported range, instead of letting a version mismatch surface
 * later as an obscure {@code NoSuchMethodError} mid-test (design doc section 5.3).
 *
 * <p>Runs first among this starter's auto-configurations so an unsupported engine is reported
 * before any other capability tries to use it.
 */
@AutoConfiguration(afterName = "org.flowable.spring.boot.ProcessEngineAutoConfiguration")
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(name = "org.flowable.engine.RuntimeService")
@ConditionalOnBean(ProcessEngine.class)
public class FlowableCompatibilityGuardAutoConfiguration {

    // Design doc section 5.4: 7.x only. Track this alongside the starter's own release line.
    static final String SUPPORTED_MIN_INCLUSIVE = "7.0.0";
    static final String SUPPORTED_MAX_EXCLUSIVE = "8.0.0";
    static final String STARTER_VERSION = "0.1.0-SNAPSHOT";

    @Bean
    InitializingBean flowableCompatibilityGuard(ProcessEngine processEngine) {
        return () -> {
            String detected = ProcessEngine.VERSION;
            if (!FlowableVersions.isSupported(detected, SUPPORTED_MIN_INCLUSIVE, SUPPORTED_MAX_EXCLUSIVE)) {
                throw new IllegalStateException(
                        ("flowable-test-spring-boot-starter %s supports Flowable [%s, %s), but the consumer " +
                         "project resolved Flowable %s. Align your org.flowable:flowable-spring-boot-starter " +
                         "version, or use a starter release matching your Flowable version -- see the " +
                         "compatibility matrix in the README.")
                                .formatted(STARTER_VERSION, SUPPORTED_MIN_INCLUSIVE, SUPPORTED_MAX_EXCLUSIVE, detected));
            }
        };
    }
}
