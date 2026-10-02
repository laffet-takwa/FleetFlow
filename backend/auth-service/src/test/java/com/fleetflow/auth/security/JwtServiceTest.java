package com.fleetflow.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtProperties;
import com.fleetflow.common.security.JwtService;

class JwtServiceTest {

    private static final String SECRET = "jwt-service-unit-test-secret-0123456789-abcdef";
    private static final String OTHER_SECRET = "a-completely-different-secret-0123456789-abcdef";

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration SKEW = Duration.ofSeconds(30);

    @Test
    void generatedTokenCarriesTheUserIdentity() {
        JwtService jwtService = jwtService(SECRET, TTL);
        // Token claims carry whole seconds, so the lower bound is truncated too.
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        String token = jwtService.generateToken(42L, "driver1@fleetflow.local", FleetRole.DRIVER);

        assertThat(token.split("\\.")).hasSize(3);
        JwtService.Claims claims = jwtService.parse(token).orElseThrow();
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.email()).isEqualTo("driver1@fleetflow.local");
        assertThat(claims.role()).isEqualTo(FleetRole.DRIVER);
        assertThat(claims.issuer()).isEqualTo("fleetflow");
        assertThat(claims.issuedAt()).isAfterOrEqualTo(before);
        assertThat(claims.expiresAt()).isEqualTo(claims.issuedAt().plus(TTL));
        assertThat(claims.isExpired(SKEW)).isFalse();
        assertThat(jwtService.getExpiresInSeconds()).isEqualTo(TTL.toSeconds());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreignToken = jwtService(OTHER_SECRET, TTL)
                .generateToken(42L, "driver1@fleetflow.local", FleetRole.ADMIN);

        assertThat(jwtService(SECRET, TTL).parse(foreignToken)).isEmpty();
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService jwtService = jwtService(SECRET, TTL);
        String[] parts = jwtService.generateToken(42L, "driver1@fleetflow.local", FleetRole.DRIVER).split("\\.");

        // Keeps the original signature but escalates the role inside the claims segment.
        String forgedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":42,\"email\":\"attacker@fleetflow.local\",\"role\":\"ADMIN\","
                        + "\"iss\":\"fleetflow\",\"iat\":1,\"exp\":9999999999}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(jwtService.parse(parts[0] + "." + forgedClaims + "." + parts[2])).isEmpty();
    }

    @Test
    void expiredTokenIsRefused() {
        Instant before = Instant.now();
        JwtService jwtService = jwtService(SECRET, Duration.ofMinutes(-5));

        String token = jwtService.generateToken(8L, "customer1@fleetflow.local", FleetRole.CUSTOMER);

        JwtService.Claims claims = jwtService.parse(token).orElseThrow();
        assertThat(claims.expiresAt()).isBefore(before);
        assertThat(claims.isExpired(Duration.ZERO)).isTrue();
        assertThat(claims.isExpired(SKEW)).isTrue();
        // Mirrors JwtAuthenticationFilter: an expired token leaves the caller anonymous.
        assertThat(jwtService.parse(token).filter(parsed -> !parsed.isExpired(SKEW))).isEmpty();
    }

    @Test
    void structurallyBrokenTokensAreRejected() {
        JwtService jwtService = jwtService(SECRET, TTL);

        assertThat(jwtService.parse(null)).isEmpty();
        assertThat(jwtService.parse("")).isEmpty();
        assertThat(jwtService.parse("not-a-token")).isEmpty();
        assertThat(jwtService.parse("a.b.c")).isEmpty();
    }

    private static JwtService jwtService(String secret, Duration ttl) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setIssuer("fleetflow");
        properties.setAccessTokenTtl(ttl);
        properties.setClockSkew(SKEW);
        return new JwtService(properties, new ObjectMapper());
    }
}