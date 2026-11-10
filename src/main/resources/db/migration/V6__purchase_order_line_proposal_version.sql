ALTER TABLE purchase_order_lines
  ADD COLUMN source_proposal_version INTEGER NOT NULL DEFAULT 0;
