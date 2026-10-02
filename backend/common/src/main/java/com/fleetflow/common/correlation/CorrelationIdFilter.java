package com.fleetflow.common.correlation;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reads {@code X-Correlation-ID} from the inbound request, generating one when the
 * caller did not supply it, and echoes the resolved value back on the response.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(CorrelationId.HEADER);
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128) {
            correlationId = CorrelationId.newId();
        }

        CorrelationId.set(correlationId);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
            CorrelationId.clear();
        }
    }
}