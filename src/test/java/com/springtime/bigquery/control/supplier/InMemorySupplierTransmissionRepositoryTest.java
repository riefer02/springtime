package com.springtime.bigquery.control.supplier;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySupplierTransmissionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemorySupplierTransmissionRepository repository =
            new InMemorySupplierTransmissionRepository(clock);

    @Test
    void enqueueIsIdempotentByPurchaseOrder() {
        SupplierTransmission first = transmission("po-1");
        SupplierTransmission second = transmission("po-1");

        assertThat(repository.enqueue(first).transmissionId()).isEqualTo(first.transmissionId());
        assertThat(repository.enqueue(second).transmissionId()).isEqualTo(first.transmissionId());
        assertThat(repository.findByPoId("po-1")).contains(first);
    }

    @Test
    void claimsDueRowsAndMovesThemToSending() {
        repository.enqueue(transmission("po-1"));

        var claimed = repository.claimDue(10);

        assertThat(claimed).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo(SupplierTransmissionStatus.SENDING);
            assertThat(value.attempts()).isEqualTo(1);
        });
        assertThat(repository.findByPoId("po-1")).get()
                .extracting(SupplierTransmission::status)
                .isEqualTo(SupplierTransmissionStatus.SENDING);
    }

    @Test
    void marksSentRetryAckedAndFailed() {
        SupplierTransmission sent = repository.enqueue(transmission("po-1"));
        long sentVersion = repository.claimDue(1).get(0).version();
        assertThat(repository.markSent(sent.transmissionId(), sentVersion, "SUP-1", NOW)).isEqualTo(1);

        assertThat(repository.find(sent.transmissionId())).get()
                .extracting(SupplierTransmission::status, SupplierTransmission::supplierReference)
                .containsExactly(SupplierTransmissionStatus.SENT, "SUP-1");

        assertThat(repository.markAcked(sent.transmissionId(),
                repository.find(sent.transmissionId()).orElseThrow().version(),
                "SUP-2", NOW, "planner")).isEqualTo(1);
        assertThat(repository.find(sent.transmissionId())).get()
                .extracting(SupplierTransmission::status, SupplierTransmission::supplierReference)
                .containsExactly(SupplierTransmissionStatus.ACKED, "SUP-2");

        SupplierTransmission retried = repository.enqueue(transmission("po-2"));
        long retriedVersion = repository.claimDue(1).get(0).version();
        assertThat(repository.markRetry(
                retried.transmissionId(), retriedVersion, "retry", NOW.plusSeconds(5))).isEqualTo(1);
        assertThat(repository.find(retried.transmissionId())).get()
                .satisfies(updated -> {
                    assertThat(updated.status()).isEqualTo(SupplierTransmissionStatus.PENDING);
                    assertThat(updated.lastError()).isEqualTo("retry");
                });

        SupplierTransmission failed = repository.enqueue(transmission("po-3"));
        long failedVersion = repository.claimDue(1).get(0).version();
        assertThat(repository.markFailed(
                failed.transmissionId(), failedVersion, "permanent")).isEqualTo(1);
        assertThat(repository.find(failed.transmissionId())).get()
                .extracting(SupplierTransmission::status)
                .isEqualTo(SupplierTransmissionStatus.FAILED);
    }

    @Test
    void staleWorkerCannotOverwriteANewerClaim() {
        SupplierTransmission value = repository.enqueue(transmission("po-1"));
        long claimedVersion = repository.claimDue(1).get(0).version();

        assertThat(repository.markSent(
                value.transmissionId(), claimedVersion + 10, "stale", NOW)).isZero();
        assertThat(repository.markSent(
                value.transmissionId(), claimedVersion, "fresh", NOW)).isEqualTo(1);
        assertThat(repository.find(value.transmissionId())).get()
                .extracting(SupplierTransmission::supplierReference)
                .isEqualTo("fresh");
    }

    @Test
    void pagesByCreationTimeWithOffset() {
        repository.enqueue(transmissionAt("po-1", NOW.minusSeconds(3)));
        repository.enqueue(transmissionAt("po-2", NOW.minusSeconds(2)));
        repository.enqueue(transmissionAt("po-3", NOW.minusSeconds(1)));

        assertThat(repository.list(null, 2, 0))
                .extracting(SupplierTransmission::poId)
                .containsExactly("po-3", "po-2");
        assertThat(repository.list(null, 2, 2))
                .extracting(SupplierTransmission::poId)
                .containsExactly("po-1");
        assertThat(repository.list(null, 2, 3)).isEmpty();
    }

    private static SupplierTransmission transmission(String poId) {
        return transmissionAt(poId, NOW);
    }

    private static SupplierTransmission transmissionAt(String poId, Instant createdAt) {
        return new SupplierTransmission(
                UUID.randomUUID(), poId, "SUP-001", SupplierTransmissionStatus.PENDING,
                0, NOW, "{}", "hash", null, null, createdAt, createdAt, null, null, 0);
    }
}
