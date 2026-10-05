package dev.patterncatalyst.shipping;

import java.time.Instant;

/**
 * Lifted byte-for-byte (field name/type/order unchanged) from the monolith's
 * {@code shipping.ShipmentDto} -- this is the read contract the strangler
 * proxy's future {@code /api/shipments} route (S7) must stay transparent
 * for (DRQ-065, ACL honesty: no translator needed since this DTO is
 * portable unchanged), so it is preserved exactly: {@code orderId} was
 * already a plain {@code Long} on the wire in the monolith (only the JPA
 * entity had the FK/join, see {@link Shipment}'s javadoc), so this record
 * needed zero changes for the FK decomposition. {@link ShipmentStatus}'s
 * extra values (PENDING/CANCELLED/FAILED, net-new here) do not change the
 * wire shape -- {@code status} is still a JSON string, and the only value
 * ever seeded/produced so far is {@code DISPATCHED}, so the contract stays
 * byte-for-byte identical to the monolith's.
 */
public record ShipmentDto(Long id, Long orderId, String address, ShipmentStatus status, Instant createdAt) {
}
