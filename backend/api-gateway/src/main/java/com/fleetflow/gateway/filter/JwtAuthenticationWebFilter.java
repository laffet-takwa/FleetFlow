package com.fleetflow.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.security.JwtPrincipal;
import com.fleetflow.common.security.JwtService;

import reactor.core.publisher.Mono;

/**
 * Verifies the {@code Bearer} access token before a request is routed.
 *
 * <p>The gateway is the trust boundary: rejecting an unusable token here means a bad
 * request never reaches a business service or the event bus. A missing token is left
 * anonymous so the authorisation rules can decide whether the path tolerates it.
 *
 * <p>Registered inside the Spring Security web filter chain, immediately before the
 * authentication filter.
 */
@Component
public class JwtAuthenticationWebFilter implements WebFilter {

    /** Exchange attribute holding the verified caller, consumed by the access log. */
    public static final String PRINCIPAL_ATTRIBUTE = "fleetflow.principal";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationWebFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        return Mono.justOrEmpty(jwtService.parse(token))
                .filter(claims -> !claims.isExpired(jwtService.getClockSkew()))
                .map(claims -> new JwtPrincipal(claims.userId(), claims.email(), claims.role()))
                .flatMap(principal -> authenticate(exchange, chain, principal))
                .switchIfEmpty(Mono.defer(() ->
                        unauthorized(exchange, "Access token is not valid or has expired")));
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain, JwtPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.role() == null
                ? List.of()
                : List.of(new SimpleGrantedAuthority(principal.role().authority()));
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        exchange.getAttributes().put(PRINCIPAL_ATTRIBUTE, principal);
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body(exchange, message).getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Emits the same error shape as the services, so clients parse failures uniformly. */
    private String body(ServerWebExchange exchange, String message) {
        // resolveOrCreate, not getFirst: this body is assembled by string concatenation,
        // so the id must be a value that cannot terminate the JSON object.
        String correlationId = CorrelationId.resolveOrCreate(
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        return ("{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"TOKEN_INVALID\",\"message\":\"%s\","
                + "\"path\":\"%s\",\"correlationId\":\"%s\"}")
                .formatted(
                        Instant.now(),
                        HttpStatus.UNAUTHORIZED.value(),
                        message,
                        exchange.getRequest().getPath().value(),
                        correlationId);
    }
}