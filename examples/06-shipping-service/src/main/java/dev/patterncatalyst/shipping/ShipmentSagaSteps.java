package dev.patterncatalyst.shipping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import org.apache.camel.Exchange;
import org.jboss.logging.Logger;

/**
 * r07/ch.24 S5 (DRQ-059/DRQ-063) -- the bodies of the Camel Saga EIP route's
 * five {@code direct:} steps ({@link ShipmentSagaRoute} wires these in as
 * the route's processors). Idiomatic-from-the-start: there is no Spring
 * original to lift (DRQ-063) -- this is adapted from the datamesh reference
 * architecture's {@code shipping-service} {@code ShipmentProcessor}
 * (~/Dev/datamesh-reference-arch-quarkus/examples/shipping-service,
 * DRQ-032), re-shaped from a single {@code @Incoming}/{@code @Outgoing}
 * CHOREOGRAPHY participant into explicit steps of an ORCHESTRATED Camel
 * Saga EIP coordinator -- the deliberate ch.23-vs-ch.24 contrast this
 * extraction teaches.
 *
 * <h2>Why "dispatch" persists PENDING, not DISPATCHED (DRQ-063 atomicity,
 * read this before changing step 2 or step 4)</h2>
 * DRQ-059's prose shorthand says step 2 "persists Shipment DISPATCHED" --
 * but DRQ-063 ALSO requires that {@code shipment.dispatched}/{@code
 * shipment.failed} be "written atomically with the Shipment state change
 * (no dual-write)", and DRQ-062 requires the Shipment row to already be
 * PERSISTED before step 3 (book carrier) can throw, specifically so
 * compensation has something to act on. Those three constraints are
 * jointly satisfiable only if step 2 and step 4 are NOT the same atomic
 * unit (step 3 sits between them and can abort the saga) -- so this
 * implementation persists {@link ShipmentStatus#PENDING} in step 2 (own
 * transaction, durable before step 3 runs), and step 4
 * ({@link #emitDispatched}) performs the ACTUAL atomic pair this step's
 * acceptance criteria cares about: the PENDING-&gt;DISPATCHED transition AND
 * the {@code shipment.dispatched} outbox row, in ONE {@code @Transactional}
 * method. The compensation path ({@link #compensate}) applies the identical
 * discipline for the failure outcome: PENDING-&gt;CANCELLED AND {@code
 * shipment.failed}, in ONE transaction. This is the most literal
 * satisfiable reading of DRQ-063's atomicity guarantee for a 4-step saga
 * with a failure point in the middle, and is why {@link ShipmentStatus#PENDING}
 * (reserved but unused by Phase A, S4) is finally exercised here.
 *
 * <h2>Why compensation correlates by {@code orderId}, not a saved {@code
 * shipmentId} option</h2>
 * The Camel Saga EIP's {@code .option(name, expression)} list is configured
 * ONCE, at the top of the {@code .saga()} block (DRQ-059 names only
 * {@code orderId}), and is evaluated against the exchange as it ENTERS the
 * saga scope -- before step 2 (dispatch) has even run, so a
 * {@code shipmentId} header set mid-route is not reliably available to
 * {@code .option(...)}. Rather than betting behavior on Camel-internal
 * evaluation-timing that isn't part of the EIP's documented contract, the
 * compensation route looks the shipment up the same way the idempotency
 * guard does: {@code ShipmentRepository#findByOrderId(orderId)}, using the
 * ONE option DRQ-059 actually guarantees is present on the compensation
 * exchange.
 */
@ApplicationScoped
public class ShipmentSagaSteps {

    private static final Logger LOG = Logger.getLogger(ShipmentSagaSteps.class);

    static final String AGGREGATE_TYPE = "shipment";

    /** DRQ-062: the deterministic carrier-booking-failure demo sentinel. */
    static final String SHIP_FAIL_SENTINEL = "SHIP-FAIL";

    static final String HEADER_ORDER_ID = "orderId";
    static final String HEADER_SHIPPING_ADDRESS = "shippingAddress";
    static final String HEADER_SHIPMENT_ID = "shipmentId";

    private final OrderReadClient orderReadClient;
    private final ShipmentRepository repository;
    private final ShipmentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public ShipmentSagaSteps(
            OrderReadClient orderReadClient,
            ShipmentRepository repository,
            ShipmentOutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.orderReadClient = orderReadClient;
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Saga step 1 (DRQ-059 "enrich"): obtains the order's shipping address.
     * See {@link OrderReadClient}'s javadoc for the full ENRICHMENT
     * CONTRACT decision and the S6 flag -- this method never fails the
     * saga on a missing/unreachable address (that would be indistinguishable
     * from the deterministic {@code SHIP-FAIL} business outcome); it always
     * receives SOME address string back.
     */
    public void enrich(Exchange exchange) {
        PaymentCaptured event = exchange.getIn().getBody(PaymentCaptured.class);
        String address = orderReadClient.fetchShippingAddress(event.orderId());
        exchange.getIn().setHeader(HEADER_SHIPPING_ADDRESS, address);
    }

    /**
     * Saga step 2 (DRQ-059 "dispatch shipment"): persists a NEW
     * {@link Shipment} row as {@link ShipmentStatus#PENDING}, in its own
     * committed transaction -- see this class's javadoc for why PENDING
     * (not DISPATCHED) is persisted here. Sets the {@code shipmentId}
     * header for step 4 to reference (same Exchange, no re-fetch needed on
     * the happy path).
     */
    @Transactional
    public void dispatchShipment(Exchange exchange) {
        Long orderId = exchange.getIn().getHeader(HEADER_ORDER_ID, Long.class);
        String address = exchange.getIn().getHeader(HEADER_SHIPPING_ADDRESS, String.class);
        Shipment shipment = new Shipment(orderId, address, ShipmentStatus.PENDING);
        repository.persist(shipment);
        exchange.getIn().setHeader(HEADER_SHIPMENT_ID, shipment.getId());
        LOG.infof("dispatch shipment: persisted PENDING shipment id=%d for order %d", shipment.getId(), orderId);
    }

    /**
     * Saga step 3 (DRQ-059 "book carrier"; DRQ-062 the deterministic
     * failure injection point): throws {@link ShipFailException} if the
     * enriched shipping address exactly equals {@value #SHIP_FAIL_SENTINEL}
     * (matching the newman Scenario 4 convention of putting the sentinel in
     * {@code shippingAddress}). Runs AFTER step 2's {@link Shipment} row is
     * already committed (DRQ-062), so compensation always has a row to act
     * on. No persistence of its own.
     */
    public void bookCarrier(Exchange exchange) {
        String address = exchange.getIn().getHeader(HEADER_SHIPPING_ADDRESS, String.class);
        Long orderId = exchange.getIn().getHeader(HEADER_ORDER_ID, Long.class);
        if (SHIP_FAIL_SENTINEL.equals(address)) {
            throw new ShipFailException(
                    "carrier booking failed for order %d: SHIP-FAIL sentinel detected".formatted(orderId));
        }
    }

    /**
     * Saga step 4 (DRQ-059 "emit shipment.dispatched"; DRQ-063 atomicity):
     * in ONE transaction, transitions the step-2 {@link Shipment} row
     * PENDING-&gt;DISPATCHED and persists the matching
     * {@link ShipmentOutboxEvent} row. Only reached if step 3 did not
     * throw -- {@code completionMode(AUTO)} then completes the saga
     * normally.
     */
    @Transactional
    public void emitDispatched(Exchange exchange) {
        Long shipmentId = exchange.getIn().getHeader(HEADER_SHIPMENT_ID, Long.class);
        Shipment shipment = repository.findByIdOptional(shipmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "shipment id=%d not found at emit step -- step 2 should have persisted it"
                                .formatted(shipmentId)));
        shipment.markDispatched();

        ShipmentDispatched payload = new ShipmentDispatched(
                shipment.getOrderId(), shipment.getId(), shipment.getAddress(), ShipmentDispatched.STATUS,
                Instant.now());
        outboxRepository.persist(buildOutboxEvent(
                ShipmentOutboxRelay.SHIPMENT_DISPATCHED_EVENT_TYPE, shipment.getOrderId(), payload));
        LOG.infof("emit shipment.dispatched: shipment id=%d order=%d now DISPATCHED",
                shipment.getId(), shipment.getOrderId());
    }

    /**
     * The coordinator-invoked compensation route ({@code
     * direct:ship-compensate}, DRQ-059) -- run by the Camel Saga EIP
     * coordinator on ANY abort (an exception anywhere in the saga body,
     * e.g. {@link ShipFailException}) or timeout, EXACTLY ONCE per saga
     * instance (Camel's {@code CamelSagaService} guarantees a saga is
     * either completed or compensated, never both, and only once).
     *
     * <p>In ONE transaction: finds the shipment for this {@code orderId}
     * (correlating by order id -- see this class's javadoc for why not a
     * saved {@code shipmentId} option); if found (the common case --
     * step 2 ran before the abort), marks it {@link ShipmentStatus#CANCELLED}
     * and classifies the failure reason from its persisted address (the
     * {@code SHIP-FAIL} sentinel vs. an unexplained abort/timeout); if NOT
     * found (the abort happened before step 2 ran, e.g. inside {@code
     * enrich} -- not expected in current code since {@link #enrich} never
     * throws, but a documented possibility for a future enrich failure
     * mode), still emits {@code shipment.failed} with a null
     * {@code shipmentId}/{@code address} rather than failing silently.
     * Either way, EXACTLY ONE {@code shipment.failed} outbox row is written
     * for this order.
     */
    @Transactional
    public void compensate(Exchange exchange) {
        Long orderId = exchange.getIn().getHeader(HEADER_ORDER_ID, Long.class);
        Optional<Shipment> maybeShipment = repository.findByOrderId(orderId);

        Long shipmentId = null;
        String address = null;
        String reason;
        if (maybeShipment.isPresent()) {
            Shipment shipment = maybeShipment.get();
            shipmentId = shipment.getId();
            address = shipment.getAddress();
            shipment.markCancelled();
            reason = SHIP_FAIL_SENTINEL.equals(address)
                    ? "carrier booking failed for order %d: SHIP-FAIL sentinel detected".formatted(orderId)
                    : "shipping saga aborted (timeout or unexpected error) for order %d".formatted(orderId);
            LOG.infof("compensate: shipment id=%d order=%d now CANCELLED (%s)", shipmentId, orderId, reason);
        } else {
            reason = "shipping saga aborted before a shipment record was created for order %d".formatted(orderId);
            LOG.infof("compensate: no shipment existed for order %d (%s)", orderId, reason);
        }

        ShipmentFailed payload = new ShipmentFailed(
                orderId, shipmentId, address, ShipmentFailed.STATUS, Instant.now(), reason);
        outboxRepository.persist(buildOutboxEvent(ShipmentOutboxRelay.SHIPMENT_FAILED_EVENT_TYPE, orderId, payload));
    }

    private ShipmentOutboxEvent buildOutboxEvent(String eventType, Long orderId, Record payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // The payload records contain only primitives/Strings/Instants --
            // this can only happen from a programming error, not bad runtime
            // data, so failing the transaction loudly is correct (no outbox
            // row with a half-serialized payload should ever be committed).
            throw new IllegalStateException("failed to serialize shipment outbox payload", e);
        }
        return new ShipmentOutboxEvent(AGGREGATE_TYPE, String.valueOf(orderId), eventType, json);
    }
}
