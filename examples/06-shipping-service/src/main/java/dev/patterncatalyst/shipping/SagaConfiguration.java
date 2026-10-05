package dev.patterncatalyst.shipping;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.apache.camel.saga.CamelSagaService;
import org.apache.camel.saga.InMemorySagaService;

/**
 * r07/ch.24 S5 (DRQ-057) -- registers the {@link InMemorySagaService} CDI
 * bean the Camel Saga EIP route (DRQ-059) looks up by name
 * ({@code .sagaService("inMemorySagaService")}, see
 * {@link ShipmentSagaRoute#SAGA_SERVICE_BEAN_NAME}) as its
 * {@link CamelSagaService} coordinator.
 *
 * <p><b>Deliberate choice, no new infra container (DRQ-057):</b>
 * {@code InMemorySagaService} is in-JVM and co-located in this service --
 * it keeps saga state (which sagas are in-flight, their registered
 * compensation/completion callbacks and {@code .option(...)} data) purely
 * in heap memory. This is an HONEST, DOCUMENTED LIMITATION, parallel to
 * ch.23's "no saga ledger": a coordinator restart (JVM crash, redeploy, pod
 * eviction) mid-saga LOSES all in-flight saga state -- a saga that was
 * between its "dispatch shipment" step (already committed, PENDING) and
 * its "emit shipment.dispatched" step at the moment of a crash would leave
 * a PENDING {@link Shipment} row forever uncompleted and uncompensated;
 * nothing would ever call its compensation or completion route, because
 * the in-memory saga state remembering that a saga was even in-flight for
 * that order is gone. {@code LRASagaService} (Narayana LRA, a distributed,
 * crash-durable coordinator) is the production-grade alternative, but it is
 * new infra (a coordinator service) -- explicitly deferred, same
 * "no new infra container" discipline ch.23 established for the choreographed
 * saga. Acceptable for this teaching example; cross-referenced to ch.25
 * (resilience) / ch.28 (contracts) in the plan.
 */
@ApplicationScoped
public class SagaConfiguration {

    @Produces
    @ApplicationScoped
    @Named(ShipmentSagaRoute.SAGA_SERVICE_BEAN_NAME)
    public CamelSagaService inMemorySagaService() {
        return new InMemorySagaService();
    }
}
