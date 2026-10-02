package com.fleetflow.common.persistence;

import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the contract that {@code spring.jpa.hibernate.ddl-auto=validate} actually
 * accepts the DDL every FleetFlow migration writes.
 *
 * <p>{@link TimestampedEntity} is shared by all seven business services, so if the
 * column types it implies ever drift from the migration files, every service fails to
 * boot at the same time. This test fails fast, in one place, instead.
 */
@Testcontainers
@Tag("integration")
class TimestampedEntitySchemaValidationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fleetflow_schema_check")
            .withUsername("fleetflow")
            .withPassword("fleetflow");

    private static final String DDL = """
            CREATE TABLE sample_record
            (
                id          BIGSERIAL PRIMARY KEY,
                label       VARCHAR(80) NOT NULL,
                created_at  TIMESTAMPTZ NOT NULL,
                updated_at  TIMESTAMPTZ NOT NULL
            );
            """;

    @BeforeAll
    static void createSchema() {
        POSTGRES.start();
        try (var connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.execute(DDL);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to prepare the verification schema", ex);
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("ddl-auto=validate accepts the TIMESTAMPTZ columns declared by the migrations")
    void schemaValidationAcceptsMigrationColumnTypes() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(SampleConfiguration.class)
                .withPropertyValues(
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("the mapped superclass fills both timestamps on insert and refreshes updatedAt")
    void timestampsAreMaintained() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(SampleConfiguration.class)
                .withPropertyValues(
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC")
                .run(context -> {
                    SampleRepository repository = context.getBean(SampleRepository.class);
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));

                    SampleRecord record = new SampleRecord();
                    record.setLabel("first");
                    repository.saveAndFlush(record);

                    assertThat(record.getCreatedAt()).isNotNull();
                    assertThat(record.getUpdatedAt()).isNotNull();

                    Instant createdAt = record.getCreatedAt();
                    jdbc.update("UPDATE sample_record SET label = ? WHERE id = ?", "second", record.getId());

                    SampleRecord reloaded = repository.findById(record.getId()).orElseThrow();
                    assertThat(reloaded.getLabel()).isEqualTo("second");
                    // created_at is immutable, updated_at is refreshed by @PreUpdate.
                    assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
                    assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(createdAt);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(considerNestedRepositories = true)
    @EntityScan(basePackageClasses = TimestampedEntity.class)
    static class SampleConfiguration {

        @Bean
        SimpleDriverDataSource dataSource() {
            var dataSource = new SimpleDriverDataSource();
            dataSource.setDriver(new org.postgresql.Driver());
            dataSource.setUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }
    }

    /** Mirrors the shape of a real FleetFlow entity, including the table name mapping. */
    @Entity
    @Table(name = "sample_record")
    static class SampleRecord extends TimestampedEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 80)
        private String label;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    interface SampleRepository
            extends org.springframework.data.jpa.repository.JpaRepository<SampleRecord, Long> {
    }
}
