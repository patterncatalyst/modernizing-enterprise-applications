package dev.patterncatalyst.payment;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * r06/ch.23 S5 (DRQ-053) -- Panache repository for this service's own outbox
 * table, mirroring the monolith's {@code common.outbox.OutboxRepository}
 * (Spring Data) idiomatically: the relay's hot query -- unpublished rows,
 * oldest first, capped at a small batch -- becomes an explicit
 * simplified-HQL {@code find(...)} call instead of a derived method name.
 */
@ApplicationScoped
public class PaymentOutboxRepository implements PanacheRepository<PaymentOutboxEvent> {

    public List<PaymentOutboxEvent> findUnpublished(int batchSize) {
        return find("publishedAt is null", Sort.ascending("createdAt"))
                .page(Page.ofSize(batchSize))
                .list();
    }
}
