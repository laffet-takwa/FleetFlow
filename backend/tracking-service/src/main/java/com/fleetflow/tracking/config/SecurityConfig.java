package com.fleetflow.tracking.config;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.security.JwtAuthenticationFilter;

/**
 * Identical in shape to every other servlet service: stateless, JWT authenticated, and
 * with security failures rendered in the platform error contract.
 *
 * <p>Rejections here are produced by the filter chain, before
 * {@code GlobalExceptionHandler} can see them, so the handlers below write the same
 * {@link ApiErrorResponse} JSON by hand.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html", "/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> writeError(response, request,
                                ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage()))
                        .accessDeniedHandler((request, response, ex) -> writeError(response, request,
                                ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage())))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, ErrorCode code,
            String message) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                Instant.now(),
                code.httpStatus().value(),
                code.name(),
                message,
                request.getRequestURI(),
                CorrelationId.getOrCreate(),
                List.of()));
    }
}
