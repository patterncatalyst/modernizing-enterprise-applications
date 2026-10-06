-- DRQ-073 follow-up (mirrors payment-service's
-- V4__retarget_demo_seed_out_of_real_order_id_range.sql): order-service was
-- decommissioned from the monolith with an EMPTY orders table and now
-- forward-fills order_id from its own BIGSERIAL sequence
-- (examples/07-order-service). In any fresh environment (every CI gate, a
-- new developer's first `podman compose up`), the FIRST real checkout is
-- therefore assigned order_id=1 -- exactly the id
-- V2__seed_demo_shipment.sql's Phase-A demo row used ("does not collide
-- with any later real checkout" was true only while order-service's own
-- table still held its pre-decommission history; it is false
-- post-decommission).
--
-- This is the SAME bug one hop downstream of payment-service: once the
-- payment-service fix above lets the real order 1's payment.captured
-- actually get emitted, ShippingService#processPaymentCaptured's
-- idempotent-by-orderId guard (backed by uq_shipments_order_id, V3) would
-- otherwise treat the real order 1 as an already-shipped duplicate against
-- this demo row -- no shipment.dispatched is emitted, and the order is
-- stuck at AWAITING_SHIPMENT instead of reaching CONFIRMED. The guard
-- itself is correct (a real redelivered payment.captured SHOULD be
-- deduped); the bug is the colliding demo DATA.
--
-- Fix: retarget the demo row's order_id out of the real forward-fill range
-- and into a reserved, clearly-synthetic high id (9,000,002 -- distinct
-- from payment-service's 9,000,001, in case any shared/joined read surface
-- ever correlates the two services' demo rows by order_id) that
-- order-service's sequence will not reach. The row itself is left in place
-- (not deleted) -- it still exercises the Phase-A read contract
-- (GET /api/shipments/{id}, GET /api/shipments?orderId=) for standalone
-- exploration of this service, just no longer in a way that can collide
-- with a real order.
--
-- Safe/idempotent to apply against either starting state: a fresh schema
-- where V2's row still sits at order_id=1 (predicate matches, single row
-- retargeted), or a database where this migration already ran (order_id is
-- now 9000002, the WHERE order_id = 1 predicate matches nothing, no-op).
UPDATE shipments
SET order_id = 9000002
WHERE order_id = 1
  AND address = '1 Analytical Engine Way, London'
  AND status = 'DISPATCHED';
