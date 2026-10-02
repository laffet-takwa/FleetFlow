package com.fleetflow.delivery.config;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
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

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
            ObjectMapper objectMapper) throws Exception {

        AuthenticationEntryPoint unauthenticated = (request, response, ex) ->
                writeError(response, objectMapper, ErrorCode.UNAUTHORIZED, request);
        AccessDeniedHandler forbidden = (request, response, ex) ->
                writeError(response, objectMapper, ErrorCode.FORBIDDEN, request);

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**",
                                 "/swagger-ui.html", "/internal/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(unauthenticated)
                .accessDeniedHandler(forbidden))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Spring Security rejects before the controller advice runs, so it renders the same payload itself. */
    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code,
            HttpServletRequest request) throws IOException {

        HttpStatus status = code.httpStatus();
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code.name(),
                code.defaultMessage(),
                request.getRequestURI(),
                CorrelationId.getOrCreate(),
                List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
