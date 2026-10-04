package com.fleetflow.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.api.GlobalExceptionHandler;
import com.fleetflow.common.correlation.CorrelationIdFilter;
import com.fleetflow.common.security.InternalServiceAuthFilter;
import com.fleetflow.common.security.InternalTokenProperties;
import com.fleetflow.common.security.JwtAuthenticationFilter;
import com.fleetflow.common.security.JwtService;

/**
 * Servlet-only wiring from the shared kernel: the correlation filter, the internal
 * service-to-service filter, the uniform error contract, and the JWT filter that each
 * business service adds to its Spring Security chain.
 *
 * <p>This lives apart from {@link FleetFlowCommonAutoConfiguration} on purpose. Spring
 * introspects every {@code @Bean} method signature on a class when it evaluates its
 * conditions, so a single class holding servlet types would fail on the reactive API
 * gateway with {@code NoClassDefFoundError: jakarta/servlet/Filter} — the gateway is a
 * WebFlux application with no servlet API on its classpath. Splitting them means the
 * gateway never loads a class that mentions a servlet type, which is stronger than
 * guarding each bean with {@code @ConditionalOnWebApplication}.
 */
@AutoConfiguration(after = FleetFlowCommonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FleetFlowWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    /**
     * The JWT filter belongs exclusively to the Spring Security chain. Left enabled as a
     * plain servlet filter it would also run outside the authorisation rules, so servlet
     * auto-registration is disabled and each service adds it with
     * {@code addFilterBefore} explicitly.
     */
    @Bean
    @ConditionalOnMissingBean(name = "jwtAuthenticationFilterRegistration")
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        // Ahead of everything, so downstream logs carry the id for the whole request.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public InternalServiceAuthFilter internalServiceAuthFilter(InternalTokenProperties internalTokenProperties,
            ObjectMapper objectMapper) {
        return new InternalServiceAuthFilter(internalTokenProperties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "internalServiceAuthFilterRegistration")
    public FilterRegistrationBean<InternalServiceAuthFilter> internalServiceAuthFilterRegistration(
            InternalServiceAuthFilter filter, InternalTokenProperties internalTokenProperties) {
        FilterRegistrationBean<InternalServiceAuthFilter> registration = new FilterRegistrationBean<>(filter);
        // Ahead of the security chain, so an unauthenticated internal call is rejected
        // outright rather than reaching any endpoint.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        // Registered from the configured prefix, not the default one: a deployment that
        // moves the paths would otherwise leave the filter attached to a prefix nothing
        // serves, which is indistinguishable from having no gate at all.
        registration.addUrlPatterns(internalTokenProperties.getPathPrefix() + "**");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}