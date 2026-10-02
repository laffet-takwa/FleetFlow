package com.fleetflow.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared secret used for service-to-service calls on {@code /internal/**} endpoints.
 *
 * <p>Internal endpoints are deliberately not exposed through the API gateway, so
 * they can only be reached from inside the platform network and only with this token.
 */
@ConfigurationProperties(prefix = "fleetflow.internal")
public class InternalTokenProperties {

    /** Header carrying the shared secret on service-to-service calls. */
    public static final String HEADER = "X-Internal-Token";

    /** Default path prefix guarded by the internal token. */
    public static final String DEFAULT_PATH_PREFIX = "/internal/";

    /** Secret compared against the {@code X-Internal-Token} header. */
    private String token = "fleetflow-local-internal-token";

    /** Path prefix guarded by the internal token. */
    private String pathPrefix = DEFAULT_PATH_PREFIX;

    private boolean enabled = true;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}