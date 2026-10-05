-- Phase-A demonstration seed only (ch.17 S4). Before S5 lands the SmallRye
-- `order.placed` consumer, this service's own `notifications` table has no
-- other way to be populated — a brand-new event-driven service has nothing
-- to lift data FROM. One row for customerId=1 (the same demo customer the
-- monolith's seed data and the behavior-equivalence suite use) proves the
-- read surface's shape end-to-end: GET /api/notifications?customerId=1
-- returns a real NotificationDto, not just an empty array. S5's consumer
-- supersedes this seed as the table's real population mechanism.
INSERT INTO notification.notifications (customer_id, order_id, channel, message, sent_at)
VALUES (1, 1, 'EMAIL', 'Order #1 confirmed, total $39.98', TIMESTAMPTZ '2026-01-07T12:00:15Z');
