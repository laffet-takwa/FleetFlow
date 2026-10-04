package com.fleetflow.gateway.error;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fleetflow.common.correlation.CorrelationId;

import reactor.core.publisher.Mono;

/**
 * Renders gateway-level failures in the same JSON contract the business services use,
 * so the SPA has exactly one error shape to parse no matter where a request failed.
 */
@Component
@Order(-1)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolve(ex);
        String error = status.name();
        String message = status == HttpStatus.NOT_FOUND
                ? "No route matches " + exchange.getRequest().getMethod() + " "
                        + exchange.getRequest().getPath().value()
                : (ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage());

        // The correlation id is resolved the same way the filter resolves it, so a
        // request that arrived without one still gets a stable id in the error body —
        // and it is never rendered as the literal string "null". resolveOrCreate also
        // constrains it to identifier characters, which matters because the body below
        // is assembled by string concatenation.
        String correlationId = CorrelationId.resolveOrCreate(
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));

        // A 5xx is ours, not the caller's: log the throwable so the cause is
        // diagnosable. The client still receives only the generic message.
        if (status.is5xxServerError()) {
            log.error("Gateway failed on {} {} [correlationId={}]",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
                    correlationId, ex);
        } else {
            log.warn("Gateway rejected {} {} -> {} [correlationId={}]",
                    exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
                    status.value(), correlationId);
        }

        String payload = ("{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\","
                + "\"path\":\"%s\",\"correlationId\":\"%s\",\"violations\":[]}")
                .formatted(
                        Instant.now(),
                        status.value(),
                        error,
                        message.replace("\"", "'"),
                        exchange.getRequest().getPath().value(),
                        correlationId);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().add("X-Error-Source", "fleetflow-api-gateway");
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(payload.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private HttpStatus resolve(Throwable ex) {
        if (ex instanceof ResponseStatusException statusException) {
            return HttpStatus.resolve(statusException.getStatusCode().value());
        }
        return ex instanceof java.util.concurrent.TimeoutException
                ? HttpStatus.GATEWAY_TIMEOUT
                : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}