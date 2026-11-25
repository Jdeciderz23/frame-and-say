package com.groupphoto.consensus.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;

class BootstrapActorsTest {
    private static final String ORGANIZER = "bootstrap_organizer_test_only_token";
    private static final String PARTICIPANT = "bootstrap_participant_test_only_token";

    @ParameterizedTest
    @ValueSource(strings = {"demo", "local", "test"})
    void enabledDevelopmentProfilesHaveTwoDistinctIdentities(String profile) {
        var actors = actors(true, profile);
        assertThat(actors.introspect(ORGANIZER).getName()).isEqualTo("organizer");
        assertThat(actors.introspect(PARTICIPANT).getName()).isEqualTo("participant");
        assertThat(actors.isProvisioned("organizer")).isTrue();
        assertThat(actors.isProvisioned("participant")).isTrue();
        assertThat(actors.isProvisioned("another_account")).isFalse();
    }

    @Test
    void theDefaultDemoProfileIsRecognized() {
        var environment = new MockEnvironment();
        environment.setDefaultProfiles("demo");
        var actors = new BootstrapActors(true, ORGANIZER, PARTICIPANT, environment);
        assertThat(actors.introspect(ORGANIZER).getName()).isEqualTo("organizer");
    }

    @Test
    void disabledBootstrapIgnoresEvenConfiguredTokens() {
        var actors = actors(false, "production");
        assertThat(actors.isProvisioned("organizer")).isFalse();
        assertThat(actors.isProvisioned("participant")).isFalse();
        assertThatThrownBy(() -> actors.introspect(ORGANIZER)).isInstanceOf(BadOpaqueTokenException.class);
        assertThatThrownBy(() -> actors.introspect(PARTICIPANT)).isInstanceOf(BadOpaqueTokenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "staging"})
    void nonDevelopmentProfilesCannotEnableSyntheticIdentities(String profile) {
        assertThatThrownBy(() -> actors(true, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted to demo, local and test");
    }

    @Test
    void addingDemoToAProductionProfileDoesNotEnableBootstrap() {
        assertThatThrownBy(() -> actors(true, "production", "demo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTokensDoNotProvisionAccounts() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        var actors = new BootstrapActors(true, "", "", environment);
        assertThat(actors.isProvisioned("organizer")).isFalse();
        assertThatThrownBy(() -> actors.introspect(ORGANIZER)).isInstanceOf(BadOpaqueTokenException.class);
    }

    @Test
    void malformedAndDuplicateTokensAreRejected() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assertThatThrownBy(() -> new BootstrapActors(true, "short", PARTICIPANT, environment))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BootstrapActors(true, ORGANIZER, ORGANIZER, environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BootstrapActors actors(boolean enabled, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new BootstrapActors(enabled, ORGANIZER, PARTICIPANT, environment);
    }
}
