package dev.patterncatalyst.order;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * ch.26 S6 (DRQ-067) — Panache repository for the CQRS read model. {@link
 * OrderService#getById}/{@link OrderService#listAll} are the ONLY read
 * callers in this service (grep-verified: no {@code orderRepository.find}
 * call exists anywhere in the read path) — every {@code GET} this service
 * serves comes from HERE, never from {@link OrderRepository} (the
 * write-model aggregate repository). A broken {@link OrderViewProjector}
 * therefore cannot be masked by a silent fallback to the aggregate — see
 * {@code OrderViewProjectionDisabledTest} for the negative check that proves
 * this.
 */
@ApplicationScoped
public class OrderViewRepository implements PanacheRepository<OrderView> {
}
