package com.fleetflow.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Issues and verifies FleetFlow access tokens.
 *
 * <p>Tokens are compact HS256 JWTs built directly on {@link Mac} rather than pulling
 * in a third party library: the algorithm is fixed and deliberately small, which
 * keeps the trust boundary easy to audit.
 *
 * <p>Verification is constant time, checks the signature before parsing any claim,
 * and refuses tokens whose issuer, role or expiry shape does not match. The signing
 * algorithm is fixed here and never read back out of the header, so neither an
 * {@code alg: none} token nor an algorithm substitution can be honoured.
 */
@Component
public class JwtService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String CLAIM_SUBJECT = "sub";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ISSUER = "iss";
    private static final String CLAIM_ISSUED_AT = "iat";
    private static final String CLAIM_EXPIRES_AT = "exp";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final JwtProperties properties;
    private final ObjectMapper objectMapper;

    public JwtService(JwtProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Claims extracted from a verified token. */
    public record Claims(Long userId, String email, FleetRole role, String issuer, Instant issuedAt, Instant expiresAt) {
        /**
         * @param clockSkew tolerance for a token that expired moments ago on an issuer
         *                  whose clock runs slightly ahead
         */
        public boolean isExpired(Duration clockSkew) {
            return Instant.now().isAfter(expiresAt.plus(clockSkew));
        }
    }

    public String generateToken(Long userId, String email, FleetRole role) {
        if (role == null) {
            // Issuing a token that parse() refuses would lock the account out silently.
            throw new IllegalArgumentException("A role is required to issue an access token");
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(CLAIM_SUBJECT, userId);
        claims.put(CLAIM_EMAIL, email);
        claims.put(CLAIM_ROLE, role.name());
        claims.put(CLAIM_ISSUER, properties.getIssuer());
        claims.put(CLAIM_ISSUED_AT, issuedAt.getEpochSecond());
        claims.put(CLAIM_EXPIRES_AT, expiresAt.getEpochSecond());

        try {
            String encodedHeader = ENCODER.encodeToString(objectMapper.writeValueAsBytes(header));
            String encodedClaims = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            String signature = ENCODER.encodeToString(sign(signingInput));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to issue access token", ex);
        }
    }

    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = sign(signingInput);
        byte[] actualSignature;
        try {
            actualSignature = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return Optional.empty();
        }
        try {
            Map<?, ?> claims = objectMapper.readValue(DECODER.decode(parts[1]), Map.class);
            Number subject = (Number) claims.get(CLAIM_SUBJECT);
            String issuer = (String) claims.get(CLAIM_ISSUER);
            if (subject == null || !properties.getIssuer().equals(issuer)) {
                return Optional.empty();
            }
            Number issuedAt = (Number) claims.get(CLAIM_ISSUED_AT);
            Number expiresAt = (Number) claims.get(CLAIM_EXPIRES_AT);
            if (expiresAt == null) {
                // A token with no expiry would verify forever; refuse rather than treat
                // a missing claim as "does not expire".
                return Optional.empty();
            }
            FleetRole role = FleetRole.from((String) claims.get(CLAIM_ROLE));
            if (role == null) {
                // A role outside the platform vocabulary cannot be mapped onto an
                // authority, and admitting it would yield an authenticated caller that
                // every role rule silently declines to reason about.
                return Optional.empty();
            }
            return Optional.of(new Claims(
                    subject.longValue(),
                    (String) claims.get(CLAIM_EMAIL),
                    role,
                    issuer,
                    issuedAt == null ? null : Instant.ofEpochSecond(issuedAt.longValue()),
                    Instant.ofEpochSecond(expiresAt.longValue())));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public long getExpiresInSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    public Duration getClockSkew() {
        return properties.getClockSkew();
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute JWT signature", ex);
        }
    }
}