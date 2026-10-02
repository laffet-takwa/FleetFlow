package com.fleetflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT settings, bound from {@code fleetflow.jwt.*}.
 *
 * <p>The secret is supplied by the {@code JWT_SECRET} environment variable in every
 * deployed environment; the default exists only so the stack can boot locally.
 */
@ConfigurationProperties(prefix = "fleetflow.jwt")
public class JwtProperties {

    /** HMAC-SHA256 signing secret, must be at least 32 bytes for HS256. */
    private String secret = "fleetflow-local-development-secret-key-change-me-0123456789";

    private String issuer = "fleetflow";

    /** Access token lifetime. */
    private java.time.Duration accessTokenTtl = java.time.Duration.ofHours(1);

    /** Tolerance for clock skew between services when checking {@code exp}. */
    private java.time.Duration clockSkew = java.time.Duration.ofSeconds(30);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public java.time.Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(java.time.Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public java.time.Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(java.time.Duration clockSkew) {
        this.clockSkew = clockSkew;
    }
}