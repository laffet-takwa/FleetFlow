package com.fleetflow.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fleetflow.common.correlation.CorrelationId;

import reactor.core.publisher.Mono;

/**
 * Establishes the {@code X-Correlation-ID} for every request that crosses the gateway.
 *
 * <p>An id supplied by the caller is honoured when it is safe to echo, otherwise one is
 * generated: the value reaches a response header, JSON error bodies assembled by string
 * concatenation, and the access log, so it is restricted to identifier characters by
 * {@link CorrelationId#resolveOrCreate}. It is attached to the forwarded request, so the
 * same id appears in the gateway access log, in each downstream service, and in every
 * Kafka event produced while handling the request.
 *
 * <p>This is a {@link WebFilter} rather than a {@code GlobalFilter} on purpose. Spring
 * Cloud Gateway runs its {@code WebFilter} chain (CORS, security) before the global
 * filter chain, and the CORS filter returns a <em>decorated</em> exchange whose response
 * headers are read-only. Writing the response header from a global filter therefore
 * fails with {@code UnsupportedOperationException: ReadOnlyHttpHeaders}. Running first
 * means the id is already on the request before any other filter or filter chain
 * observes it.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));

        // Only the request header is written here. The response header is deliberately
        // left to the service that handled the request: it echoes the id it received and
        // the gateway copies that single value back. Writing it here as well produced two
        // X-Correlation-ID headers on every proxied response. Responses the gateway
        // generates itself - no matching route, an upstream failure - are covered by
        // GatewayErrorHandler instead.
        ServerWebExchange mutated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(CorrelationId.HEADER, correlationId)))
                .build();
        return chain.filter(mutated);
    }

    private String resolve(String candidate) {
        return CorrelationId.resolveOrCreate(candidate);
    }

    @Override
    public int getOrder() {
        // Ahead of CORS and the security filters, so the header exists for the whole chain.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}