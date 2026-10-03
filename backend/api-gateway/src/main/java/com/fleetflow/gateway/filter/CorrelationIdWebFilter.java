package com.fleetflow.gateway.filter;

import java.util.function.Consumer;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

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
 * <p>This is a {@link WebFilter} rather than a {@code GlobalFilter} on purpose. Spring
 * Cloud Gateway runs its {@code WebFilter} chain (CORS, security) before the global
 * filter chain, and the CORS filter returns a <em>decorated</em> exchange whose response
 * headers are read-only. Setting the response header from a global filter therefore
 * fails with {@code UnsupportedOperationException: ReadOnlyHttpHeaders}. Running first
 * means the headers are still mutable, and the id is already on the request before any
 * other filter or filter chain observes it.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));

        Consumer<ServerHttpResponse> responseDecorator =
                response -> response.getHeaders().set(CorrelationId.HEADER, correlationId);
        responseDecorator.accept(exchange.getResponse());

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
        // Ahead of CORS and the security filters, so the header exists for the whole chain.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}