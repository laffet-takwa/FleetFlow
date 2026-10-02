package com.fleetflow.common.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import com.fleetflow.common.api.GlobalExceptionHandler;
import com.fleetflow.common.correlation.CorrelationIdFilter;
import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.KafkaDomainEventPublisher;
import com.fleetflow.common.idempotency.IdempotencyService;
import com.fleetflow.common.security.InternalServiceAuthFilter;
import com.fleetflow.common.security.InternalTokenProperties;
import com.fleetflow.common.security.JwtAuthenticationFilter;
import com.fleetflow.common.security.JwtProperties;
import com.fleetflow.common.security.JwtService;

/**
 * Wires the shared kernel into any service that puts {@code fleetflow-common} on its
 * classpath. Services therefore get correlation IDs, the uniform error contract,
 * JWT verification and idempotent Kafka consumption without repeating any setup.
 *
 * <p>The API gateway is reactive, so servlet specific beans are guarded with
 * {@code @ConditionalOnWebApplication(SERVLET)}: the gateway depends on this module
 * for the event contracts and the JWT verifier only, and imports no MVC infrastructure.
 */
@AutoConfiguration(before = KafkaAutoConfiguration.class)
@EnableConfigurationProperties({ JwtProperties.class, InternalTokenProperties.class })
public class FleetFlowCommonAutoConfiguration {

    // ---------------------------------------------------------------- security

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(JwtProperties jwtProperties, ObjectMapper objectMapper) {
        return new JwtService(jwtProperties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    /**
     * The JWT filter belongs exclusively to the Spring Security chain. Left enabled as
     * a plain servlet filter it would also run outside the authorisation rules.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = "jwtAuthenticationFilterRegistration")
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // ------------------------------------------------------------- correlation

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(CorrelationIdFilter filter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    // ------------------------------------------------- service-to-service auth

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public InternalServiceAuthFilter internalServiceAuthFilter(InternalTokenProperties internalTokenProperties,
            ObjectMapper objectMapper) {
        return new InternalServiceAuthFilter(internalTokenProperties, objectMapper);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = "internalServiceAuthFilterRegistration")
    public FilterRegistrationBean<InternalServiceAuthFilter> internalServiceAuthFilterRegistration(
            InternalServiceAuthFilter filter) {
        FilterRegistrationBean<InternalServiceAuthFilter> registration = new FilterRegistrationBean<>(filter);
        // Runs before the security chain so an unauthenticated internal call is
        // rejected outright instead of reaching any endpoint.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns(InternalTokenProperties.DEFAULT_PATH_PREFIX + "**");
        return registration;
    }

    // ---------------------------------------------------------- error contract

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    // ------------------------------------------------------------------- kafka

    /**
     * Explicit {@code String -> String} producer so events cross the bus as plain
     * JSON, matching {@code docs/kafka-events.md} exactly and removing the need for
     * trusted-package configuration on either side of the bus.
     */
    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, String> fleetFlowProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    public KafkaTemplate<String, String> fleetFlowKafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @ConditionalOnMissingBean(DomainEventPublisher.class)
    public DomainEventPublisher domainEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new KafkaDomainEventPublisher(kafkaTemplate, objectMapper);
    }

    // ------------------------------------------------------------- idempotency

    @Bean
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnMissingBean
    public IdempotencyService idempotencyService(JdbcTemplate jdbcTemplate,
            @Value("${fleetflow.idempotency.retention:P7D}") Duration retention) {
        return new IdempotencyService(jdbcTemplate, retention);
    }

    // ------------------------------------------------------------------ openapi

    @Bean
    @ConditionalOnClass(name = "io.swagger.v3.oas.models.OpenAPI")
    @ConditionalOnMissingBean(name = "fleetFlowOpenAPI")
    @ConditionalOnProperty(name = "fleetflow.openapi.enabled", havingValue = "true", matchIfMissing = true)
    public OpenAPI fleetFlowOpenAPI(
            @Value("${spring.application.name:FleetFlow}") String appName,
            @Value("${fleetflow.api.version:v1}") String apiVersion,
            @Value("${fleetflow.api.description:FleetFlow API}") String description) {

        return new OpenAPI()
                .info(new Info()
                        .title(appName + " API")
                        .version(apiVersion)
                        .description(description
                                + "\n\nSend an `X-Correlation-ID` header to trace a request across services and Kafka events.")
                        .contact(new Contact().name("FleetFlow"))
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Obtain a token from POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}