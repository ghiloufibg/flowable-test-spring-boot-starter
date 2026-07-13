package com.flowabletest.autoconfigure.guard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowableVersionsTest {

    @Test
    void acceptsVersionsInsideTheRange() {
        assertThat(FlowableVersions.isSupported("7.0.0", "7.0.0", "8.0.0")).isTrue();
        assertThat(FlowableVersions.isSupported("7.1.0", "7.0.0", "8.0.0")).isTrue();
        assertThat(FlowableVersions.isSupported("7.9.9", "7.0.0", "8.0.0")).isTrue();
    }

    @Test
    void rejectsVersionsBelowTheMinimum() {
        assertThat(FlowableVersions.isSupported("6.8.0", "7.0.0", "8.0.0")).isFalse();
    }

    @Test
    void rejectsVersionsAtOrAboveTheExclusiveMaximum() {
        assertThat(FlowableVersions.isSupported("8.0.0", "7.0.0", "8.0.0")).isFalse();
        assertThat(FlowableVersions.isSupported("9.2.1", "7.0.0", "8.0.0")).isFalse();
    }

    @Test
    void toleratesQualifiersAndExtraSegments() {
        assertThat(FlowableVersions.isSupported("7.1.0.4", "7.0.0", "8.0.0")).isTrue();
        assertThat(FlowableVersions.isSupported("7.1.0-SNAPSHOT", "7.0.0", "8.0.0")).isTrue();
    }
}
