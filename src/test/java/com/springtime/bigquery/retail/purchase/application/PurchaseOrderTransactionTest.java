package com.springtime.bigquery.retail.purchase.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.control.JdbcTransactionRunner;
import com.springtime.bigquery.control.supplier.SupplierOutbox;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import com.springtime.bigquery.retail.purchase.store.JdbcPurchaseOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JdbcPurchaseOrderRepository.class, JdbcTransactionRunner.class})
@Sql(scripts = {"/db/migration/V2__purchase_orders.sql",
        "/db/migration/V6__purchase_order_line_proposal_version.sql",
        "/db/migration/V7__purchase_order_scope_unique.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class PurchaseOrderTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final SupplierOutbox outbox = mock(SupplierOutbox.class);
    private PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderService(
                repository,
                mock(PurchaseRunReader.class),
                mock(ApprovedProposalQuery.class),
                properties(),
                new PurchaseOrderMetrics(new SimpleMeterRegistry()),
                outbox,
                new JdbcTransactionRunner(transactionManager),
                CLOCK);
    }

    @Test
    void submitRollsBackThePurchaseOrderAndEventWhenTheOutboxFails() {
        PurchaseOrder draft = order("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        repository.create(draft);
        doThrow(new IllegalStateException("outbox down"))
                .when(outbox).enqueue(anyString(), any());

        assertThatThrownBy(() -> service.submit(draft.poId(), 0, null, null, "planner"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.find(draft.poId())).get()
                .extracting(PurchaseOrder::status)
                .isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(repository.events(draft.poId(), 10, 0)).isEmpty();
    }

    @Test
    void submitCommitsTheOrderEventAndOutboxTogether() {
        PurchaseOrder draft = order("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        repository.create(draft);

        service.submit(draft.poId(), 0, "SUP-REF", "sent", "planner");

        assertThat(repository.find(draft.poId())).get()
                .extracting(PurchaseOrder::status, PurchaseOrder::version)
                .containsExactly(PurchaseOrderStatus.SUBMITTED, 1);
        assertThat(repository.events(draft.poId(), 10, 0)).singleElement()
                .extracting(event -> event.toStatus().name())
                .isEqualTo("SUBMITTED");
    }

    private static PurchaseOrder order(String poId) {
        String suffix = poId.substring(0, 8);
        return new PurchaseOrder(
                poId, "PO-" + poId, "run:" + poId, "run-" + suffix, "ST-" + suffix,
                "SUP-" + suffix, "Supplier 1", PurchaseOrderStatus.DRAFT, "USD",
                new BigDecimal("10.00"), 1, "planner", null, LocalDate.of(2026, 11, 20),
                null, 0, NOW, null, null);
    }

    private static RetailProperties properties() {
        return new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 268_435_456L, "USD",
                Duration.ofMinutes(5), Duration.ofDays(365), 0, ZoneOffset.UTC);
    }
}
