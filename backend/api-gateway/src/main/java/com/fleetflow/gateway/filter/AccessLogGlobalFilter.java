package com.fleetflow.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.security.JwtPrincipal;

import reactor.core.publisher.Mono;

/**
 * One access log line per request, carrying the correlation id so the entry lines up
 * with the downstream service logs and with the Kafka events produced for that request.
 *
 * <p>The caller is read from the exchange attribute published by
 * {@link JwtAuthenticationWebFilter}: reading the reactive security context here would
 * mean reaching into the Reactor context from outside the pipeline, where it is gone.
 */
@Component
public class AccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange).doFinally(signalType -> write(exchange, startNanos));
    }

    private void write(ServerWebExchange exchange, long startNanos) {
        ServerHttpRequest request = exchange.getRequest();
        String status = exchange.getResponse().getStatusCode() == null
                ? "n/a"
                : String.valueOf(exchange.getResponse().getStatusCode().value());
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String correlationId = request.getHeaders().getFirst(CorrelationId.HEADER);
        Object principal = exchange.getAttributes().get(JwtAuthenticationWebFilter.PRINCIPAL_ATTRIBUTE);
        String user = principal instanceof JwtPrincipal jwt ? jwt.email() : "anonymous";

        log.info("{} {} -> {} in {}ms user={} [{}]",
                request.getMethod(), request.getURI().getPath(), status, durationMs, user, correlationId);
    }

    @Override
    public int getOrder() {
        // After correlation id resolution so the header is present, and late enough that
        // the security filters have already authenticated the caller.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}