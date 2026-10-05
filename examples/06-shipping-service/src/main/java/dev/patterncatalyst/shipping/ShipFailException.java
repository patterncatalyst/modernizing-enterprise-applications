package dev.patterncatalyst.shipping;

/**
 * r07/ch.24 S5 (DRQ-062) -- thrown by {@code ShipmentSagaSteps#bookCarrier}
 * when the deterministic {@code SHIP-FAIL} sentinel is detected, mirroring
 * payment-service's {@code CARD-DECLINE} demo rule as the shipping
 * equivalent. Thrown from WITHIN the Camel Saga EIP block
 * ({@code direct:ship-start}'s {@code .saga()} scope), so
 * {@code completionMode(AUTO)} causes the coordinator to compensate (invoke
 * {@code direct:ship-compensate}) instead of completing -- this is the ONE
 * and only deterministic abort trigger the behavior-equivalence suite and
 * CI red-then-green gate rely on (alongside a saga timeout, the other
 * documented abort path).
 */
public class ShipFailException extends RuntimeException {
    public ShipFailException(String message) {
        super(message);
    }
}
