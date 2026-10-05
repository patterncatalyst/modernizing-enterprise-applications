package dev.patterncatalyst.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * NET-NEW for r05/ch.19 S5's CDC backfill/sync consumer
 * ({@link InventoryCdcConsumer}) -- there is no Spring original to lift for
 * a concern the monolith never had (consuming its own table's change
 * stream). A plain CDI bean using {@code EntityManager} directly, NOT a
 * Spring Data repository method: Quarkus's Spring Data JPA compat extension
 * does not support {@code @Query(nativeQuery = true)} on custom repository
 * methods (confirmed empirically against this module --
 * {@code SpringDataJPAProcessor} rejects it at build time with "Attribute
 * nativeQuery of @Query is currently not supported").
 *
 * <p>{@link #upsert} is a single atomic
 * {@code INSERT ... ON CONFLICT (id) DO UPDATE} -- idempotent by
 * construction under Kafka's at-least-once redelivery, and correct whether
 * the row already exists (a streamed {@code op=u}) or not yet (an
 * initial-snapshot {@code op=r} backfill row or a new {@code op=c}).
 * {@link #deleteById} is a plain keyed delete, a no-op if the row is already
 * gone (redelivered {@code op=d}).
 */
@ApplicationScoped
public class InventoryCdcWriter {

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    public void upsert(long id, String sku, String name, long priceCents, int quantityOnHand, Instant updatedAt) {
        entityManager.createNativeQuery("""
                INSERT INTO inventory.inventory_items (id, sku, name, price_cents, quantity_on_hand, updated_at)
                VALUES (:id, :sku, :name, :priceCents, :quantityOnHand, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET
                    sku = EXCLUDED.sku,
                    name = EXCLUDED.name,
                    price_cents = EXCLUDED.price_cents,
                    quantity_on_hand = EXCLUDED.quantity_on_hand,
                    updated_at = EXCLUDED.updated_at
                """)
                .setParameter("id", id)
                .setParameter("sku", sku)
                .setParameter("name", name)
                .setParameter("priceCents", priceCents)
                .setParameter("quantityOnHand", quantityOnHand)
                .setParameter("updatedAt", updatedAt)
                .executeUpdate();
    }

    @Transactional
    public void deleteById(long id) {
        entityManager.createNativeQuery("DELETE FROM inventory.inventory_items WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }
}
