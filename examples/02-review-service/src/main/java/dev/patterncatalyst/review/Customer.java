package dev.patterncatalyst.review;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * SMELL[ch.18] CARRIED OVER, NOT CURED HERE: Review's {@code ManyToOne} FK
 * target in the monolith's one shared Postgres schema. This is a deliberately
 * MINIMAL, read-only projection of the shared {@code customers} table — just
 * the {@code id} Review's validation and {@code toDto} mapping actually touch
 * (see {@code dev.patterncatalyst.monolith.common.Customer} for the full
 * shared-kernel entity with {@code name}/{@code email}/{@code createdAt}).
 *
 * <p>DEFERRED (per r02 S8 scope): this service still points at the SAME
 * podman-stack Postgres (localhost:5432, db {@code monolith}) and the
 * existing shared tables. True database decomposition / an anti-corruption
 * layer for the customer reference is a LATER chapter (ch.18 "Data Across the
 * Seam" — shared data -> owned data; ch.19 CDC backfill), not Phase A. Phase
 * A's job is only to prove the service runs on Quarkus with the Review
 * contract unchanged.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private Long id;

    protected Customer() {
        // JPA
    }

    /** Package-private test-support constructor (see {@code TestFixtures}). */
    Customer(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
