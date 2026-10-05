package dev.patterncatalyst.shipping;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * r07/ch.24 S5 (DRQ-063) -- Panache repository for this service's own
 * outbox table, mirroring payment-service's {@code PaymentOutboxRepository}
 * (DRQ-053): the relay's hot query -- unpublished rows, oldest first,
 * capped at a small batch -- as an explicit simplified-HQL {@code find(...)}
 * call.
 */
@ApplicationScoped
public class ShipmentOutboxRepository implements PanacheRepository<ShipmentOutboxEvent> {

    public List<ShipmentOutboxEvent> findUnpublished(int batchSize) {
        return find("publishedAt is null", Sort.ascending("createdAt"))
                .page(Page.ofSize(batchSize))
                .list();
    }
}
