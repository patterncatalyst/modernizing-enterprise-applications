-- Deterministic demo seed data. Relies on BIGSERIAL identity assignment in
-- insertion order against a fresh schema (customers 1-2, inventory_items 1-3,
-- orders 1, etc.) so every context has at least one row to serve.

INSERT INTO customers (name, email, created_at) VALUES
    ('Ada Lovelace', 'ada@example.com', TIMESTAMP '2026-01-05 09:00:00'),
    ('Grace Hopper', 'grace@example.com', TIMESTAMP '2026-01-06 10:30:00');

INSERT INTO inventory_items (sku, name, price_cents, quantity_on_hand, updated_at) VALUES
    ('SKU-WIDGET-001', 'Standard Widget', 1999, 100, TIMESTAMP '2026-01-05 08:00:00'),
    ('SKU-GADGET-002', 'Deluxe Gadget', 4999, 50, TIMESTAMP '2026-01-05 08:00:00'),
    ('SKU-GIZMO-003', 'Pocket Gizmo', 999, 5, TIMESTAMP '2026-01-05 08:00:00');

-- Order #1: Ada buys two Standard Widgets, fully confirmed and shipped.
INSERT INTO orders (customer_id, status, total_cents, shipping_address, created_at) VALUES
    (1, 'CONFIRMED', 3998, '1 Analytical Engine Way, London', TIMESTAMP '2026-01-07 12:00:00');

INSERT INTO order_items (order_id, inventory_item_id, quantity, unit_price_cents) VALUES
    (1, 1, 2, 1999);

INSERT INTO payments (order_id, amount_cents, method, status, created_at) VALUES
    (1, 3998, 'CARD-VISA', 'CAPTURED', TIMESTAMP '2026-01-07 12:00:05');

INSERT INTO shipments (order_id, address, status, created_at) VALUES
    (1, '1 Analytical Engine Way, London', 'DISPATCHED', TIMESTAMP '2026-01-07 12:00:10');

INSERT INTO notifications (customer_id, order_id, channel, message, sent_at) VALUES
    (1, 1, 'EMAIL', 'Order #1 confirmed, total $39.98', TIMESTAMP '2026-01-07 12:00:15');

-- Reviews: independent of orders entirely (SMELL[ch.15] note on Review).
INSERT INTO reviews (customer_id, inventory_item_id, rating, comment, created_at) VALUES
    (2, 1, 5, 'Works great, exactly as advertised!', TIMESTAMP '2026-01-08 15:00:00'),
    (1, 2, 4, 'Solid build, a little pricey.', TIMESTAMP '2026-01-09 11:00:00');
