package com.fleetflow.notification.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.security.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
                .authenticationEntryPoint((request, response, ex) ->
                        write(request, response, ErrorCode.UNAUTHORIZED, ex.getMessage()))
                .accessDeniedHandler((request, response, ex) ->
                        write(request, response, ErrorCode.FORBIDDEN, ex.getMessage())))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Spring Security rejects before the shared {@code GlobalExceptionHandler} is
     * reached, so the entry point renders the same {@link ApiErrorResponse} body itself
     * and a client sees one error shape whatever produced the rejection.
     */
    private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {

        response.setStatus(code.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                Instant.now(),
                code.httpStatus().value(),
                code.name(),
                detail == null || detail.isBlank() ? code.defaultMessage() : detail,
                request.getRequestURI(),
                CorrelationId.getOrCreate(),
                List.of()));
    }
}
