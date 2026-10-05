-- r06/ch.23 S4: a small own-seed row so the lifted read surface
-- (GET /api/payments/{id}, GET /api/payments?orderId=) has something to
-- return before the S5 order.placed consumer exists to populate the table
-- forward. UNLIKE inventory-service (CDC backfill from an existing monolith
-- table), payment rows are created forward only by the saga -- this seed is
-- optional/minimal, purely to exercise the Phase A read contract, and uses
-- an orderId (1) that does not collide with any later real checkout in this
-- own, isolated schema.
INSERT INTO payments (order_id, amount_cents, method, status, created_at)
VALUES (1, 3998, 'CARD-VISA', 'CAPTURED', '2026-01-07T12:00:05Z');
