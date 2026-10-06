package dev.patterncatalyst.contracts;

import dev.patterncatalyst.contracts.avro.OrderPlaced;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

/**
 * ch.28 (DRQ-038 follow-up, DRQ-076) — the Avro+Apicurio DEMONSTRATOR
 * producer. Emits a generated {@link OrderPlaced} SpecificRecord (built from
 * {@code src/main/avro/order-placed-v1.avsc} by the quarkus-avro extension)
 * onto this module's OWN {@code order.events.avro.demo} topic, via the
 * {@code order-avro-out} channel's {@code AvroKafkaSerializer}, which
 * registers the schema with Apicurio on first send
 * ({@code apicurio.registry.auto-register=true}).
 *
 * <p>Deliberately standalone: no outbox, no database, no correlation to the
 * real order aggregate (examples/07-order-service) — this is a teaching
 * surface for the Avro/registry mechanics, not a second production event
 * source. A {@code POST /demo/orders} call synthesizes one demo
 * {@link OrderPlaced} event and emits it immediately; mirrors the
 * {@code @Channel}/{@link Emitter} idiom every outbox relay in this repo
 * already uses (e.g. {@code examples/07-order-service}'s
 * {@code OrderOutboxRelay}), minus the outbox persistence step.
 */
@Path("/demo/orders")
@ApplicationScoped
public class OrderAvroProducer {

    private static final Logger LOG = Logger.getLogger(OrderAvroProducer.class);

    private final AtomicLong nextOrderId = new AtomicLong(1);

    @Channel("order-avro-out")
    Emitter<OrderPlaced> orderAvroEmitter;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public OrderPlaced emitDemoOrder() {
        OrderPlaced event = buildDemoEvent();
        orderAvroEmitter.send(event);
        LOG.infof("schema-registry-demo: emitted demo OrderPlaced orderId=%d to order.events.avro.demo",
                event.getOrderId());
        return event;
    }

    private OrderPlaced buildDemoEvent() {
        long orderId = nextOrderId.getAndIncrement();
        return OrderPlaced.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(1L)
                .setCustomerEmail("ada@example.com")
                .setTotalCents(2599L)
                .setPaymentMethod("CARD-VISA")
                .setConfirmationMessage("Thanks for your order #" + orderId + " (Avro demo)")
                .setPlacedAt(Instant.now())
                .build();
    }
}
