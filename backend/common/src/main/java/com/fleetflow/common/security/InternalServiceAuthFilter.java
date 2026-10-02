package com.fleetflow.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards {@code /internal/**} endpoints, which are only ever called by another
 * FleetFlow service rather than by a user. They sit outside the gateway route table,
 * so this token is the only thing protecting them.
 */
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    private final InternalTokenProperties properties;
    private final ObjectMapper objectMapper;

    public InternalServiceAuthFilter(InternalTokenProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled()
                || !request.getRequestURI().startsWith(properties.getPathPrefix());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String provided = request.getHeader(InternalTokenProperties.HEADER);
        boolean authorised = provided != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                        properties.getToken().getBytes(StandardCharsets.UTF_8));

        if (!authorised) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "timestamp", java.time.Instant.now().toString(),
                            "status", HttpStatus.FORBIDDEN.value(),
                            "error", "FORBIDDEN",
                            "message", "A valid " + InternalTokenProperties.HEADER + " header is required",
                            "path", request.getRequestURI())));
            return;
        }
        chain.doFilter(request, response);
    }
}