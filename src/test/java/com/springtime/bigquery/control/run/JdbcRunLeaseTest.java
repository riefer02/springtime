package com.springtime.bigquery.control.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JdbcRunLease.class, JdbcRunLeaseTest.ClockConfig.class})
@Sql(scripts = "/db/migration/V5__control_run_leases.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class JdbcRunLeaseTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcRunLease lease;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void oneOwnerWinsAndReleaseIsOwnerScoped() {
        assertThat(lease.tryAcquire("run-1", "owner-a", Duration.ofMinutes(5))).isTrue();
        assertThat(lease.tryAcquire("run-1", "owner-b", Duration.ofMinutes(5))).isFalse();
        assertThat(lease.tryAcquire("run-1", "owner-a", Duration.ofMinutes(5))).isTrue();

        lease.release("run-1", "owner-b");
        assertThat(lease.tryAcquire("run-1", "owner-b", Duration.ofMinutes(5))).isFalse();

        lease.release("run-1", "owner-a");
        assertThat(lease.tryAcquire("run-1", "owner-b", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void anExpiredLeaseCanBeReclaimed() {
        assertThat(lease.tryAcquire("run-2", "owner-a", Duration.ofMinutes(5))).isTrue();

        jdbcClient.sql("""
                        UPDATE control_run_leases
                        SET expires_at = :expired
                        WHERE run_id = :runId
                        """)
                .param("expired", java.sql.Timestamp.from(NOW.minusSeconds(60)))
                .param("runId", "run-2")
                .update();

        assertThat(lease.tryAcquire("run-2", "owner-b", Duration.ofMinutes(5))).isTrue();
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
