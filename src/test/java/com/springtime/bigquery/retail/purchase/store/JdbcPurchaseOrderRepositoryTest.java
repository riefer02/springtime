package com.springtime.bigquery.retail.purchase.store;

import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderEvent;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLineDraft;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcPurchaseOrderRepository.class)
@Sql(scripts = {"/db/migration/V2__purchase_orders.sql",
        "/db/migration/V6__purchase_order_line_proposal_version.sql",
        "/db/migration/V7__purchase_order_scope_unique.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class JdbcPurchaseOrderRepositoryTest {

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
    private JdbcPurchaseOrderRepository repository;

    @Test
    void createsFindsAndListsWithLines() {
        PurchaseOrder order = order("11111111-1111-1111-1111-111111111111", PurchaseOrderStatus.DRAFT, 0);
        repository.create(order);
        repository.insertLines(order, List.of(proposal()), Instant.now());

        assertThat(repository.find("11111111-1111-1111-1111-111111111111")).get()
                .extracting(PurchaseOrder::poId, PurchaseOrder::status)
                .containsExactly("11111111-1111-1111-1111-111111111111", PurchaseOrderStatus.DRAFT);
        assertThat(repository.findByIdempotencyKey(order.idempotencyKey())).isPresent();
        assertThat(repository.list(null, null, null, 10, 0)).hasSize(1);
        assertThat(repository.lines("11111111-1111-1111-1111-111111111111", 10, 0)).singleElement().satisfies(line -> {
            assertThat(line.productId()).isEqualTo("SKU-1");
            assertThat(line.orderedUnits()).isEqualTo(10);
            assertThat(line.sourceProposalVersion()).isEqualTo(1);
        });
    }

    @Test
    void insertsManyLinesInOneBatch() {
        PurchaseOrder order = order("55555555-5555-5555-5555-555555555555",
                PurchaseOrderStatus.DRAFT, 0);
        repository.create(order);
        List<PurchaseOrderLineDraft> proposals = java.util.stream.IntStream.range(0, 250)
                .mapToObj(JdbcPurchaseOrderRepositoryTest::proposal)
                .toList();

        repository.insertLines(order, proposals, Instant.now());

        assertThat(repository.lines(order.poId(), 1_000, 0)).hasSize(250);
    }

    @Test
    void submitsAndCancelsWithOptimisticVersion() {
        PurchaseOrder submitted = order("22222222-2222-2222-2222-222222222222", PurchaseOrderStatus.DRAFT, 0);
        repository.create(submitted);
        assertThat(repository.submit("22222222-2222-2222-2222-222222222222", 0, "SUP-REF-1", Instant.now())).isEqualTo(1);
        assertThat(repository.find("22222222-2222-2222-2222-222222222222")).get()
                .extracting(PurchaseOrder::status, PurchaseOrder::version)
                .containsExactly(PurchaseOrderStatus.SUBMITTED, 1);

        PurchaseOrder cancelled = order("33333333-3333-3333-3333-333333333333", PurchaseOrderStatus.DRAFT, 0);
        repository.create(cancelled);
        assertThat(repository.cancel("33333333-3333-3333-3333-333333333333", 0, Instant.now())).isEqualTo(1);
        assertThat(repository.find("33333333-3333-3333-3333-333333333333")).get()
                .extracting(PurchaseOrder::status)
                .isEqualTo(PurchaseOrderStatus.CANCELLED);

        assertThat(repository.submit("33333333-3333-3333-3333-333333333333", 0, null, Instant.now())).isZero();
    }

    @Test
    void oneScopeCanOnlyBeInsertedOnce() {
        PurchaseOrder first = order("77777777-7777-7777-7777-777777777777",
                "run-scope", "ST-SCOPE", "SUP-SCOPE", PurchaseOrderStatus.DRAFT, 0);
        PurchaseOrder second = order("88888888-8888-8888-8888-888888888888",
                "run-scope", "ST-SCOPE", "SUP-SCOPE", PurchaseOrderStatus.DRAFT, 0);

        repository.create(first);
        repository.create(second);

        assertThat(repository.findByScope("run-scope", "ST-SCOPE", "SUP-SCOPE"))
                .get().extracting(PurchaseOrder::poId)
                .isEqualTo(first.poId());
        assertThat(repository.find(second.poId())).isEmpty();
    }

    @Test
    void aCancelledScopeCanBeReplaced() {
        PurchaseOrder first = order("99999999-9999-9999-9999-999999999999",
                "run-replace", "ST-REPLACE", "SUP-REPLACE", PurchaseOrderStatus.DRAFT, 0);
        repository.create(first);
        assertThat(repository.cancel(first.poId(), 0, Instant.now())).isEqualTo(1);

        PurchaseOrder replacement = order("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "run-replace", "ST-REPLACE", "SUP-REPLACE", PurchaseOrderStatus.DRAFT, 0);
        repository.create(replacement);

        assertThat(repository.find(replacement.poId())).isPresent();
    }

    @Test
    void recordsLifecycleEvents() {
        PurchaseOrder order = order("44444444-4444-4444-4444-444444444444", PurchaseOrderStatus.DRAFT, 0);
        repository.create(order);
        repository.recordEvent(new PurchaseOrderEvent(
                UUID.randomUUID().toString(), "44444444-4444-4444-4444-444444444444", null, PurchaseOrderStatus.DRAFT,
                "planner", "created", Instant.now()));

        assertThat(repository.events("44444444-4444-4444-4444-444444444444", 10, 0)).singleElement().satisfies(event -> {
            assertThat(event.fromStatus()).isNull();
            assertThat(event.toStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
            assertThat(event.actor()).isEqualTo("planner");
        });
    }

    private static PurchaseOrder order(String poId, PurchaseOrderStatus status, int version) {
        String suffix = poId.substring(0, 8);
        return order(poId, "run-" + suffix, "ST-" + suffix, "SUP-" + suffix, status, version);
    }

    private static PurchaseOrder order(String poId, String runId, String storeId,
                                       String supplierId, PurchaseOrderStatus status, int version) {
        Instant now = Instant.now();
        return new PurchaseOrder(
                poId, "PO-" + poId, runId + ":" + storeId + ":" + supplierId + ":" + poId.substring(0, 8), runId, storeId,
                supplierId, "Supplier 1", status, "USD", new BigDecimal("10.00"), 1,
                "planner", null, LocalDate.of(2026, 11, 20), null, version,
                now, null, null);
    }

    private static PurchaseOrderLineDraft proposal() {
        return proposal(1);
    }

    private static PurchaseOrderLineDraft proposal(int index) {
        return new PurchaseOrderLineDraft(
                "SKU-" + index, "SKU-" + index, "Product SKU-" + index, "SUP-001",
                10, new BigDecimal("10.00"), 1);
    }
}
