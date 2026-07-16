package com.flowabletest.autoconfigure.diagnostics;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code flowable.test.diagnostics.*}, replacing what used to be scattered {@code @Value}
 * defaults on {@link FlowableTestDiagnosticsAutoConfiguration}'s {@code @Bean} methods with one
 * typed surface: {@code spring-boot-configuration-processor} (already a dependency of this module)
 * generates IDE autocomplete/description metadata from this record's Javadoc, and {@code
 * ignoreUnknownFields = false} means a misspelled key under this prefix fails context refresh
 * instead of silently falling back to a default.
 *
 * <p>{@link #enabled()} is bound here purely so a typo of that key is still caught by the same
 * unknown-field check; it is not read by this record's consumer. The actual on/off switch for
 * {@link FlowableTestDiagnosticsAutoConfiguration} remains its class-level
 * {@code @ConditionalOnProperty}, which necessarily runs before this properties bean would exist.
 */
@ConfigurationProperties(prefix = "flowable.test.diagnostics", ignoreUnknownFields = false)
public record FlowableTestDiagnosticsProperties(
    /** Whether the BPMN failure-diagnostics capability is active at all. */
    @DefaultValue("true") boolean enabled,

    /**
     * How many process instances a single test failure will run full diagnostics queries against;
     * instances started beyond this cap are omitted, with a count of how many.
     */
    @DefaultValue("50") int maxTrackedProcessInstances,

    /** How many entries of a process instance's activity trail a diagnostics snapshot includes. */
    @DefaultValue("20") int maxActivityTrailEntries,

    /** How many characters of a single process variable's rendered value a snapshot includes. */
    @DefaultValue("500") int maxVariableValueLength,

    /**
     * How many variable-history entries (across all variables, oldest dropped first) a snapshot
     * includes.
     */
    @DefaultValue("50") int maxVariableHistoryEntries,

    /** Whether a diagnostics snapshot includes dead-letter job failures. */
    @DefaultValue("true") boolean includeFailedJobs,

    /**
     * Process variable names (case-insensitive substring match) rendered as {@code [REDACTED]}
     * rather than dumped verbatim into a snapshot.
     */
    @DefaultValue({"password", "token", "secret", "apikey", "authorization", "ssn"})
        List<String> redactedVariableNames) {}
