package dev.patterncatalyst.order;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * ch.26 S5 (DRQ-073) -- Panache repository for this service's own outbox
 * table, mirroring payment-service's {@code PaymentOutboxRepository} exactly:
 * the relay's hot query -- unpublished rows, oldest first, capped at a small
 * batch -- is an explicit simplified-HQL {@code find(...)} call.
 */
@ApplicationScoped
public class OrderOutboxRepository implements PanacheRepository<OrderOutboxEvent> {

    public List<OrderOutboxEvent> findUnpublished(int batchSize) {
        return find("publishedAt is null", Sort.ascending("createdAt"))
                .page(Page.ofSize(batchSize))
                .list();
    }
}
