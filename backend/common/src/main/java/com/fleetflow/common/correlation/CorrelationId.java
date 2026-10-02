package com.fleetflow.common.correlation;

import java.util.UUID;

/**
 * Holder for the correlation identifier that stitches together a single business
 * request: gateway -&gt; service -&gt; Kafka event -&gt; consuming service.
 *
 * <p>The identifier is stored in a {@link ThreadLocal} and mirrored into the SLF4J
 * MDC by {@link CorrelationIdFilter} so every log line can be grouped afterwards.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationId() {
    }

    /** Returns the current correlation id, or {@code null} outside a request scope. */
    public static String get() {
        return CURRENT.get();
    }

    /** Returns the current correlation id, generating a fresh one when absent. */
    public static String getOrCreate() {
        String existing = CURRENT.get();
        return existing != null ? existing : newId();
    }

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(correlationId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}