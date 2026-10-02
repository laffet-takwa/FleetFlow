package com.fleetflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fleetflow.common.correlation.CorrelationId;

import reactor.core.publisher.Mono;

/**
 * Establishes the {@code X-Correlation-ID} for every request that crosses the gateway.
 *
 * <p>An id supplied by the caller is honoured, otherwise one is generated. The value is
 * written back on the response and attached to the forwarded request, so the same id
 * appears in the gateway access log, in each downstream service, and in every Kafka
 * event produced while handling the request.
 *
 * <p>The id travels in the request header rather than a thread local: a reactive
 * pipeline hops between event loop threads, so a thread local set here would not be
 * visible downstream.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        // The response object is shared with every downstream exchange, so setting the
        // header up front is enough for it to reach the client.
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        ServerWebExchange mutated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(CorrelationId.HEADER, correlationId)))
                .build();
        return chain.filter(mutated);
    }

    private String resolve(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 128) {
            return CorrelationId.newId();
        }
        return candidate;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}