-- DRQ-073 follow-up: order-service was decommissioned from the monolith
-- with an EMPTY orders table and now forward-fills order_id from its own
-- BIGSERIAL sequence (examples/07-order-service). In any fresh environment
-- (every CI gate, a new developer's first `podman compose up`), the FIRST
-- real checkout is therefore assigned order_id=1 -- exactly the id
-- V2__seed_demo_payment.sql's Phase-A demo row used ("does not collide with
-- any later real checkout" was true only while order-service's own table
-- still held its pre-decommission history; it is false post-decommission).
--
-- PaymentService#processOrderPlaced is idempotent by orderId
-- (`if (repository.findByOrderId(orderId) != null) return;`, backed by
-- uq_payments_order_id, V1) -- CORRECT behavior for a genuinely redelivered
-- order.placed event. The bug is not that guard; it's that the demo seed
-- row sat in the same id space a real order can land in, so the very first
-- real checkout in a fresh environment is silently treated as an
-- already-processed duplicate: no payment.captured is ever emitted, and the
-- order is stuck at PENDING forever (CI: "order 1 status after 20
-- attempt(s): expected 'PENDING' to deeply equal 'CONFIRMED'").
--
-- Fix: retarget the demo row's order_id out of the real forward-fill range
-- and into a reserved, clearly-synthetic high id (9,000,001) that
-- order-service's sequence will not reach. The row itself is left in place
-- (not deleted) -- it still exercises the Phase-A read contract
-- (GET /api/payments/{id}, GET /api/payments?orderId=) for standalone
-- exploration of this service, just no longer in a way that can collide
-- with a real order.
--
-- Safe/idempotent to apply against either starting state: a fresh schema
-- where V2's row still sits at order_id=1 (predicate matches, single row
-- retargeted), or a database where this migration already ran (order_id is
-- now 9000001, the WHERE order_id = 1 predicate matches nothing, no-op).
UPDATE payments
SET order_id = 9000001
WHERE order_id = 1
  AND amount_cents = 3998
  AND method = 'CARD-VISA'
  AND status = 'CAPTURED';
