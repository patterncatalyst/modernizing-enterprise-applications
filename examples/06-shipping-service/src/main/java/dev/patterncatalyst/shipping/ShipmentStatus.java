package dev.patterncatalyst.shipping;

/**
 * Extends the monolith's {@code shipping.ShipmentStatus} (a single
 * {@code DISPATCHED} value, since the monolith only ever dispatches
 * in-process and never fails or cancels a shipment) with the states this
 * service's model needs going forward (r07/ch.24 S4, DRQ-063):
 *
 * <ul>
 *   <li>{@link #PENDING} -- a shipment row created before the S5 saga's
 *       "book carrier" step has run (the model's natural starting state;
 *       the monolith never needed this because it created a {@code Shipment}
 *       synchronously, already DISPATCHED, in one step).</li>
 *   <li>{@link #DISPATCHED} -- unchanged from the monolith; the happy-path
 *       terminal state.</li>
 *   <li>{@link #CANCELLED} -- net-new, needed by the S5 saga's compensation
 *       leg ({@code direct:ship-compensate}): on a forced failure, a
 *       {@code Shipment} already persisted by the saga body is cancelled
 *       rather than deleted, preserving an auditable record of the aborted
 *       attempt (DRQ-059).</li>
 *   <li>{@link #FAILED} -- net-new, the terminal status recorded on this
 *       entity when the saga's compensation fires before any {@code Shipment}
 *       row existed (the model's complement to {@link #CANCELLED}).</li>
 * </ul>
 *
 * None of these extra states are produced yet -- Phase A (this step) lifts
 * only the read surface; the S5 saga is what writes {@code PENDING}/
 * {@code CANCELLED}/{@code FAILED} rows. The wire-level {@code ShipmentDto}
 * shape is unaffected (still a {@code String} on the JSON contract), so the
 * read contract stays byte-for-byte identical to the monolith's for the only
 * value ever seeded/produced so far ({@code DISPATCHED}).
 */
public enum ShipmentStatus {
    PENDING,
    DISPATCHED,
    CANCELLED,
    FAILED
}
