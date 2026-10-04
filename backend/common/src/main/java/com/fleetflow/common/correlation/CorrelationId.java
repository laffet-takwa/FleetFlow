package com.fleetflow.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

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

    /**
     * A caller supplied id is echoed back in a response header, interpolated into JSON
     * error bodies that are assembled by string concatenation, mirrored into the MDC
     * and written to the access log. Restricting it to identifier characters means a
     * hostile {@code X-Correlation-ID} cannot terminate the JSON object, forge a log
     * line or smuggle a control character into any of those.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationId() {
    }

    /**
     * Honours a caller supplied id when it is safe to echo, and mints a fresh one
     * otherwise.
     *
     * @return {@code candidate} itself, or a new id
     */
    public static String resolveOrCreate(String candidate) {
        return isSafe(candidate) ? candidate : newId();
    }

    /** @return {@code true} when {@code candidate} may be echoed back to the caller and into logs. */
    public static boolean isSafe(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches();
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