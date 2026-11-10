# ADR 0014 — Purchase orders from approved proposals

- **Status:** Accepted
- **Date:** 2026-11-09
- **Related:** ADR 0012 (runs/proposals), ADR 0013 (proposal decisions), ADR 0015 (Postgres control plane)

## Context

Approved proposals need to become a supplier-facing commitment. The system needs a durable draft,
copied lines, totals, lifecycle status, and an audit trail. ADR 0015 moves this state to Postgres so
creation, submission, and outbox enqueue are transactional; approved proposals are read from
BigQuery at creation time.

## Decisions

- **One PO per run/store/supplier.** The request names the run, store, and supplier. Only proposals
  in `APPROVED` state are copied into lines.
- **Idempotency key.** A client may supply `idempotencyKey`; otherwise the server derives
  `runId:storeId:supplierId`. A retry returns the existing PO instead of creating a duplicate.
- **Draft lifecycle.** `DRAFT` → `SUBMITTED` or `CANCELLED`. Submission and cancellation require
  `expectedVersion`; the update is conditional on the stored version and status.
- **Append-only audit.** `purchase_order_events` records creation, submission, and cancellation with
  actor, note, and timestamp.
- **Lines are a copy.** Product, SKU, name, ordered units, line value, and the source proposal
  version are copied from the approved proposal at creation. Later proposal changes do not mutate
  an existing PO; `source_proposal_version` makes the snapshot auditable. A PO is bounded to 1,000
  lines and lines are inserted as one JDBC batch.
- **Supplier transmission is a boundary, not a fake.** `submit` records the internal state and an
  optional `supplierReference`, and enqueues the durable supplier outbox. An EDI/API adapter can
  attach to the outbox worker; the service does not claim a real supplier order was transmitted.
- **Receiving is deferred.** Partial receipts, invoice matching, and inventory movement generation
  are future workflows.

## Consequences

- Duplicate creation is controlled by the idempotency key; a cancelled key requires a new key.
- Creation is header insert + line copy + audit insert in one Postgres transaction.
- Submission, audit insertion, and outbox enqueue are one Postgres transaction. A failure rolls
  back the PO state and the outbox row together.
- PO creation snapshots approved proposals read from BigQuery. A decision made between that read and
  the Postgres insert does not retroactively change the PO; revalidating inside the transaction
  would hold a Postgres transaction across a BigQuery call, so the snapshot is intentional.
- Supplier acknowledgement is implemented as an operator endpoint (`POST /supplier-transmissions/
  {id}/ack`) over the durable Postgres outbox. Real EDI/API transmission, receiving, and invoice
  matching remain open; the bundled supplier gateway is a local demo.
