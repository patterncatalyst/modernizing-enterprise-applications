package dev.patterncatalyst.monolith.common.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The relay's hot query: unpublished rows, oldest first, capped at a
     * small batch so one scheduled tick never holds an unbounded amount of
     * work. Backed by {@code idx_outbox_unpublished} (V3__outbox.sql).
     */
    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
}
