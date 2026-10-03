package com.fleetflow.common.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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

import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.KafkaDomainEventPublisher;
import com.fleetflow.common.idempotency.IdempotencyService;
import com.fleetflow.common.security.InternalTokenProperties;
import com.fleetflow.common.security.JwtProperties;
import com.fleetflow.common.security.JwtService;

/**
 * Wires the shared kernel into any service that puts {@code fleetflow-common} on its
 * classpath: JWT issuing and verification, Kafka publishing, consumer idempotency and
 * the OpenAPI definition. A service therefore gets those without repeating any setup.
 *
 * <p>Everything servlet-specific lives in {@link FleetFlowWebAutoConfiguration}. This
 * class must stay free of servlet types: Spring introspects every {@code @Bean} method
 * signature while evaluating the conditions on it, and the reactive API gateway — which
 * has no servlet API on its classpath — loads this class.
 *
 * <p>Ordering is declared on both sides. {@code after DataSourceAutoConfiguration}
 * because the idempotency condition looks for a {@code DataSource} bean, and
 * {@code before KafkaAutoConfiguration} so the explicit producer factory below wins
 * over Boot's generic one.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = KafkaAutoConfiguration.class)
@EnableConfigurationProperties({ JwtProperties.class, InternalTokenProperties.class })
public class FleetFlowCommonAutoConfiguration {

    // ---------------------------------------------------------------- security

    @Bean
    @ConditionalOnMissingBean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Token issuing and verification. The servlet filter that consumes it is registered
     * by {@link FleetFlowWebAutoConfiguration}; the reactive gateway uses this service
     * directly from its own web filter.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(JwtProperties jwtProperties, ObjectMapper objectMapper) {
        return new JwtService(jwtProperties, objectMapper);
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

    /**
     * Only registered where a relational database actually exists. The tracking service
     * depends on this module for the event contracts but runs on MongoDB and Redis, and
     * excludes the DataSource auto-configuration — so the condition has to be a single
     * {@code DataSource} bean, not {@code JdbcTemplate} being on the classpath.
     *
     * <p>Without {@code after = DataSourceAutoConfiguration} this class would be
     * evaluated first (its own name sorts before {@code org.springframework}), see no
     * DataSource yet, and silently skip the bean in every service.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
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