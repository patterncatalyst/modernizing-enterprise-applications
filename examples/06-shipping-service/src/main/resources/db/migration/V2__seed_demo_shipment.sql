-- r07/ch.24 S4: a small own-seed row so the lifted read surface
-- (GET /api/shipments/{id}, GET /api/shipments?orderId=) has something to
-- return before the S5 orchestrated saga exists to populate the table
-- forward. UNLIKE inventory-service (CDC backfill from an existing monolith
-- table), shipment rows are created forward only by the saga -- this seed is
-- optional/minimal, purely to exercise the Phase A read contract, and uses
-- an orderId (1) that does not collide with any later real checkout in this
-- own, isolated schema (mirrors payment-service's V2 demo seed, DRQ-052/
-- DRQ-063).
INSERT INTO shipments (order_id, address, status, created_at)
VALUES (1, '1 Analytical Engine Way, London', 'DISPATCHED', '2026-01-07T12:00:10Z');
