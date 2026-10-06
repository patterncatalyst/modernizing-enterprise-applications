package dev.patterncatalyst.contracts;

import dev.patterncatalyst.contracts.avro.OrderPlaced;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

/**
 * ch.28 (DRQ-038 follow-up, DRQ-076) — the Avro+Apicurio DEMONSTRATOR
 * consumer. Deserializes {@code order.events.avro.demo} back into the
 * generated {@link OrderPlaced} SpecificRecord (the {@code order-avro-in}
 * channel's {@code AvroKafkaDeserializer}, with {@code use-specific-avro-
 * reader=true}, resolves the writer's schema id against the same Apicurio
 * registry the producer registered against). Records every event received
 * so tests (and the demo script, indirectly) can assert field-for-field
 * equality against what {@link OrderAvroProducer} sent — the round-trip
 * proof that Avro + the registry preserved the contract across the wire.
 */
@ApplicationScoped
public class OrderAvroConsumer {

    private static final Logger LOG = Logger.getLogger(OrderAvroConsumer.class);

    private final List<OrderPlaced> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<OrderPlaced> last = new AtomicReference<>();

    @Incoming("order-avro-in")
    public void consume(OrderPlaced event) {
        LOG.infof("schema-registry-demo: consumed OrderPlaced orderId=%d customerEmail=%s totalCents=%d",
                event.getOrderId(), event.getCustomerEmail(), event.getTotalCents());
        received.add(event);
        last.set(event);
    }

    /** All events consumed so far, in receipt order (test/demo introspection only). */
    public List<OrderPlaced> received() {
        return List.copyOf(received);
    }

    /** The most recently consumed event, or {@code null} if none yet. */
    public OrderPlaced last() {
        return last.get();
    }
}
