package com.groupphoto.consensus.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

/**
 * Explicitly enabled synthetic identities for demo/local/test integration. Disabled bootstrap
 * or missing tokens keeps business endpoints closed. Replace this when connecting a real identity provider.
 */
@Component
public class BootstrapActors implements OpaqueTokenIntrospector {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,256}");
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("demo", "local", "test");
    private final byte[] organizerToken;
    private final byte[] participantToken;

    BootstrapActors(@Value("${app.bootstrap.enabled:false}") boolean enabled,
                    @Value("${app.bootstrap.organizer-token:}") String organizer,
                    @Value("${app.bootstrap.participant-token:}") String participant,
                    Environment environment) {
        if (!enabled) {
            organizerToken = null;
            participantToken = null;
            return;
        }
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        if (profiles.length == 0 || !Arrays.stream(profiles).allMatch(DEVELOPMENT_PROFILES::contains)) {
            throw new IllegalArgumentException("Bootstrap identities are restricted to demo, local and test profiles");
        }
        organizerToken = configuredToken(organizer);
        participantToken = configuredToken(participant);
        if (participantToken != null && matches(organizerToken, participantToken)) {
            throw new IllegalArgumentException("Bootstrap actors must have distinct tokens");
        }
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        byte[] supplied = token.getBytes(StandardCharsets.US_ASCII);
        boolean organizer = matches(organizerToken, supplied);
        boolean participant = matches(participantToken, supplied);
        if (!organizer && !participant) {
            throw new BadOpaqueTokenException("Invalid bearer token");
        }
        String actorId = organizer ? "organizer" : "participant";
        return new DefaultOAuth2AuthenticatedPrincipal(actorId, Map.of("sub", actorId), List.of());
    }

    public boolean isProvisioned(String actorId) {
        return switch (actorId) {
            case "organizer" -> organizerToken != null;
            case "participant" -> participantToken != null;
            default -> false;
        };
    }

    private static byte[] configuredToken(String token) {
        if (token.isEmpty()) {
            return null;
        }
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Bootstrap tokens must use 32 to 256 base64url characters");
        }
        return token.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean matches(byte[] expected, byte[] supplied) {
        return expected != null && MessageDigest.isEqual(expected, supplied);
    }
}
