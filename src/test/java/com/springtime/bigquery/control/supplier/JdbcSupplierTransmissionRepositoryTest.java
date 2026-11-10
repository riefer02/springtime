package com.springtime.bigquery.control.supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcSupplierTransmissionRepository.class)
@Sql(scripts = {"/db/migration/V1__supplier_outbox.sql",
        "/db/migration/V3__supplier_outbox_hardening.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class JdbcSupplierTransmissionRepositoryTest {

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
    private JdbcSupplierTransmissionRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void enqueueClaimsMarksSentAndAcked() {
        SupplierTransmission transmission = transmission("po-1");
        repository.enqueue(transmission);

        var claimed = repository.claimDue(10);
        assertThat(claimed).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo(SupplierTransmissionStatus.SENDING);
            assertThat(value.attempts()).isEqualTo(1);
        });
        long claimedVersion = claimed.get(0).version();

        assertThat(repository.markSent(
                transmission.transmissionId(), claimedVersion, "SUP-1", Instant.now())).isEqualTo(1);
        assertThat(repository.find(transmission.transmissionId())).get()
                .extracting(SupplierTransmission::status, SupplierTransmission::supplierReference)
                .containsExactly(SupplierTransmissionStatus.SENT, "SUP-1");

        repository.markAcked(transmission.transmissionId(),
                repository.find(transmission.transmissionId()).orElseThrow().version(),
                "SUP-ACK", Instant.now(), "planner");
        assertThat(repository.find(transmission.transmissionId())).get()
                .extracting(SupplierTransmission::status, SupplierTransmission::supplierReference)
                .containsExactly(SupplierTransmissionStatus.ACKED, "SUP-ACK");
    }

    @Test
    void enqueueIsIdempotentByPoAndRetryWorks() {
        SupplierTransmission first = transmission("po-2");
        SupplierTransmission second = transmission("po-2");

        assertThat(repository.enqueue(first).transmissionId()).isEqualTo(first.transmissionId());
        assertThat(repository.enqueue(second).transmissionId()).isEqualTo(first.transmissionId());

        long claimedVersion = repository.claimDue(1).get(0).version();
        assertThat(repository.markFailed(first.transmissionId(), claimedVersion, "permanent"))
                .isEqualTo(1);
        assertThat(repository.retry(first.transmissionId(),
                repository.find(first.transmissionId()).orElseThrow().version(),
                Instant.now().minusSeconds(1), "planner")).isEqualTo(1);

        assertThat(repository.find(first.transmissionId())).get()
                .extracting(SupplierTransmission::status, SupplierTransmission::attempts)
                .containsExactly(SupplierTransmissionStatus.PENDING, 0);
    }

    @Test
    void summaryReadsDoNotLoadThePayload() {
        SupplierTransmission transmission = transmission("po-summary");
        repository.enqueue(transmission);

        assertThat(repository.find(transmission.transmissionId()).orElseThrow().payload()).isNull();
        assertThat(repository.findByPoId("po-summary").orElseThrow().payload()).isNull();
        assertThat(repository.claimDue(1)).singleElement()
                .extracting(SupplierTransmission::payload)
                .isNotNull();
    }

    @Test
    void claimDueDoesNotHandTheSameRowToTwoWorkers() {
        repository.enqueue(transmission("po-claim"));

        assertThat(repository.claimDue(10)).hasSize(1);
        assertThat(repository.claimDue(10)).isEmpty();
    }

    @Test
    void reclaimsSendingRowsLeftByACrashedWorker() {
        SupplierTransmission transmission = transmission("po-stale");
        repository.enqueue(transmission);
        repository.claimDue(1);

        jdbcClient.sql("""
                        UPDATE supplier_transmissions
                        SET updated_at = now() - INTERVAL '11 minutes'
                        WHERE transmission_id = :id
                        """)
                .param("id", transmission.transmissionId())
                .update();

        assertThat(repository.claimDue(1)).singleElement()
                .extracting(SupplierTransmission::attempts)
                .isEqualTo(2);
    }

    private static SupplierTransmission transmission(String poId) {
        Instant now = Instant.now().minusSeconds(1);
        return new SupplierTransmission(
                UUID.randomUUID(), poId, "SUP-001", SupplierTransmissionStatus.PENDING,
                0, now, "{}", "hash", null, null, now, now, null, null, 0);
    }
}
