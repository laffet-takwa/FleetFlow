package com.fleetflow.common.correlation;

import org.slf4j.MDC;

/**
 * Restores a correlation id for the duration of a block, typically the body of a
 * Kafka listener: the id travels with the event, so downstream logs and any events
 * republished while handling it keep pointing at the same originating request.
 *
 * <pre>{@code
 * try (CorrelationIdScope ignored = CorrelationIdScope.open(envelope.correlationId())) {
 *     handle(envelope);
 * }
 * }</pre>
 */
public final class CorrelationIdScope implements AutoCloseable {

    private final String previousCorrelationId;
    private final String previousMdcValue;
    private final boolean hadMdcValue;

    private CorrelationIdScope(String correlationId) {
        this.previousCorrelationId = CorrelationId.get();
        this.previousMdcValue = MDC.get(CorrelationId.MDC_KEY);
        this.hadMdcValue = MDC.getCopyOfContextMap() != null && previousMdcValue != null;
        CorrelationId.set(correlationId);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
    }

    /** Opens a scope for {@code correlationId}; a null or blank id simply clears the context. */
    public static CorrelationIdScope open(String correlationId) {
        return new CorrelationIdScope(correlationId);
    }

    /** Restores whatever correlation context was active before this scope opened. */
    @Override
    public void close() {
        CorrelationId.set(previousCorrelationId);
        if (hadMdcValue) {
            MDC.put(CorrelationId.MDC_KEY, previousMdcValue);
        } else {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}