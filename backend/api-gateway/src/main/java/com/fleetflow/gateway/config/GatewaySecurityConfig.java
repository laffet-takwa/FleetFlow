package com.fleetflow.gateway.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.gateway.filter.JwtAuthenticationWebFilter;

import reactor.core.publisher.Mono;

/**
 * Edge security policy.
 *
 * <p>Token verification happens once here in {@link JwtAuthenticationWebFilter}; this
 * configuration only decides which paths may be reached anonymously. Authorisation
 * itself stays with the owning service, which knows whether the caller is allowed to
 * touch that particular order, delivery or profile.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({ GatewaySecurityProperties.class, GatewayCorsProperties.class })
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            JwtAuthenticationWebFilter jwtAuthenticationWebFilter,
            GatewaySecurityProperties properties) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(properties.getAlwaysOpenPaths().toArray(String[]::new)).permitAll()
                        .pathMatchers(properties.getPublicPaths().toArray(String[]::new)).permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, ex) -> writeError(exchange, HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "Authentication is required"))
                        .accessDeniedHandler((exchange, ex) -> writeError(exchange, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "You are not allowed to perform this action")))
                .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public CorsWebFilter corsWebFilter(GatewayCorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", properties.toCorsConfiguration());
        return new CorsWebFilter(source);
    }

    private static Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String payload = ("{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"%s\",\"message\":\"%s\","
                + "\"path\":\"%s\",\"correlationId\":\"%s\"}")
                .formatted(
                        Instant.now(),
                        status.value(),
                        error,
                        message,
                        exchange.getRequest().getPath().value(),
                        exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(payload.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}