package dev.patterncatalyst.order;

import org.springframework.data.jpa.repository.JpaRepository;

/** Lifted byte-for-byte from the monolith's {@code order.OrderRepository} (ch.26 S4, Phase A). */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
