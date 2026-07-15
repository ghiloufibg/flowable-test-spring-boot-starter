package com.flowabletest.debugui;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code flowable.test.debug-ui.*}. {@code ignoreUnknownFields = false} means a misspelled
 * key under this prefix fails context refresh instead of silently keeping its default, same as
 * {@code FlowableTestDiagnosticsProperties}.
 */
@ConfigurationProperties(prefix = "flowable.test.debug-ui", ignoreUnknownFields = false)
public record DebugUiProperties(
    /** Whether the debug UI capability is active at all. Off by default -- opt-in only. */
    @DefaultValue("false") boolean enabled,

    /** Port the debug UI's HTTP server binds to. {@code 0} (default) means an OS-assigned port. */
    @DefaultValue("0") int port) {}
