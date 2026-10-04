package com.fleetflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Strips {@code Transfer-Encoding} from a proxied response so the gateway re-frames it.
 *
 * <p>Spring Cloud Gateway copies the downstream headers onto the client response. That
 * includes {@code Transfer-Encoding: chunked}, which the downstream service produced.
 * Netty's behaviour when that header is present is to assume the body is already
 * framed and to write it through untouched — and because the payload arriving from the
 * downstream service genuinely is chunked, the result looks almost right except that
 * the terminating zero-length chunk is never written.
 *
 * <p>The visible symptom is a truncated body: lenient clients (browsers, curl) read it
 * and shrug, while a strict HTTP parser rejects the connection with
 * {@code HTTPParserError: Response does not match the HTTP/1.1 protocol (Invalid EOF state)}.
 * A dropped byte at the end of a JSON payload is the kind of defect that stays hidden
 * until it corrupts something downstream.
 *
 * <p>Removing the header hands framing back to Netty, which chunks the response itself
 * and terminates it correctly.
 *
 * <p>Ordering is the whole subtlety: the header has to be removed after the route has
 * been resolved and the response headers have been copied in, but before the response
 * is written. {@code NettyRoutingFilter} runs at {@code LOWEST_PRECEDENCE} and
 * {@code NettyWriteResponseFilter} at {@code -1}, so this sits immediately below the
 * router and immediately above the writer.
 */
@Component
public class RemoveTransferEncodingGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).doOnSuccess(
                ignored -> exchange.getResponse().getHeaders().remove(HttpHeaders.TRANSFER_ENCODING));
    }

    @Override
    public int getOrder() {
        // Just below NettyRoutingFilter (LOWEST_PRECEDENCE) and above
        // NettyWriteResponseFilter (-1), where the response is still mutable.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}