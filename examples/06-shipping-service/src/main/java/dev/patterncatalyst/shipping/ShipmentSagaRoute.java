package dev.patterncatalyst.shipping;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.TimeUnit;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.SagaCompletionMode;
import org.apache.camel.model.SagaPropagation;
import org.apache.camel.saga.CamelSagaService;

/**
 * r07/ch.24 S5 (DRQ-056/057/058/059) -- the Camel Saga EIP ORCHESTRATOR: a
 * single, top-to-bottom-readable route that explicitly sequences the
 * fulfilment steps and registers the compensating action, the deliberate
 * contrast to ch.23's CHOREOGRAPHED payment saga (no coordinator; each
 * service reacts independently to events). Adapted from the datamesh
 * reference architecture's {@code shipping-service}
 * ({@code ~/Dev/datamesh-reference-arch-quarkus/examples/shipping-service},
 * DRQ-032) -- that example is a pure choreography participant
 * ({@code @Incoming}/{@code @Outgoing}); this route re-shapes the same
 * "consume payment.captured, dispatch, emit" idea into explicit Saga EIP
 * steps with a named coordinator and an explicit compensation leg.
 *
 * <h2>The saga shape (DRQ-059)</h2>
 * <pre>
 * from("direct:ship-start")
 *   .saga()
 *     .sagaService("inMemorySagaService")   // DRQ-057
 *     .propagation(REQUIRES_NEW)
 *     .completionMode(AUTO)
 *     .timeout(...)
 *     .compensation("direct:ship-compensate")
 *     .option("orderId", header("orderId"))
 *   .to("direct:ship-enrich")          // step 1
 *   .to("direct:ship-dispatch")        // step 2 -- DRQ-062: persists BEFORE book-carrier can throw
 *   .to("direct:ship-book-carrier")    // step 3 -- the deterministic SHIP-FAIL throw point
 *   .to("direct:ship-emit-dispatched") // step 4 -- only reached on success
 * </pre>
 * Each {@code direct:} endpoint delegates to one method on
 * {@link ShipmentSagaSteps} -- see that class's javadoc for the
 * transaction/atomicity design per step and why compensation correlates by
 * {@code orderId} rather than a saved {@code shipmentId} saga option.
 *
 * <h2>How the coordinator invokes compensation EXACTLY ONCE on ANY
 * abort/timeout</h2>
 * {@code completionMode(AUTO)} means the {@link org.apache.camel.saga.InMemorySagaService}
 * coordinator (registered by {@link SagaConfiguration}) watches the outcome
 * of the wrapped step chain: if it completes without an exception, the saga
 * is marked complete (no compensation call); if ANY step throws (here,
 * only {@link ShipFailException} from {@code bookCarrier} is deterministic,
 * but ANY unexpected exception from enrich/dispatch/emit would behave
 * identically) OR the configured {@code .timeout(...)} elapses before
 * completion, the coordinator calls the ONE registered
 * {@code .compensation("direct:ship-compensate")} endpoint -- Camel's saga
 * SPI guarantees a given saga instance is compensated or completed, never
 * both, and at most once. The original failure is then re-thrown to the
 * caller of {@code direct:ship-start} (handled by
 * {@link ShippingService#processPaymentCaptured}, which logs it rather than
 * treating it as a consumer error).
 *
 * <p>Timeout: 15 seconds is generous for this demo's entirely in-process
 * steps (one synchronous REST enrichment call plus local Postgres writes)
 * while still being short enough that a genuinely hung saga compensates
 * well within a test's bounded-wait window.
 */
@ApplicationScoped
public class ShipmentSagaRoute extends RouteBuilder {

    /** Must match {@link SagaConfiguration}'s {@code @Named} CDI bean. */
    static final String SAGA_SERVICE_BEAN_NAME = "inMemorySagaService";

    private static final long SAGA_TIMEOUT_SECONDS = 15;

    private final ShipmentSagaSteps steps;

    public ShipmentSagaRoute(ShipmentSagaSteps steps) {
        this.steps = steps;
    }

    @Override
    public void configure() throws Exception {
        // The CDI-produced InMemorySagaService (SagaConfiguration) is a
        // plain `new InMemorySagaService()` -- CDI itself never calls
        // Camel's Service#start() lifecycle on it, which is what
        // initializes its internal ScheduledExecutorService (used for
        // timeout scheduling). Registering it explicitly with the
        // CamelContext here (during route configuration, before the
        // context finishes starting) makes Camel manage its start/stop
        // lifecycle correctly -- without this, EVERY saga invocation NPEs
        // inside InMemorySagaCoordinator#beginStep on a null executor.
        CamelSagaService sagaService =
                getContext().getRegistry().lookupByNameAndType(SAGA_SERVICE_BEAN_NAME, CamelSagaService.class);
        getContext().addService(sagaService, true, true);

        from(ShippingService.SAGA_START_ENDPOINT)
                .routeId("shipping-saga")
                .saga()
                    .sagaService(SAGA_SERVICE_BEAN_NAME)
                    .propagation(SagaPropagation.REQUIRES_NEW)
                    .completionMode(SagaCompletionMode.AUTO)
                    .timeout(SAGA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .compensation("direct:ship-compensate")
                    .option(ShipmentSagaSteps.HEADER_ORDER_ID, header(ShipmentSagaSteps.HEADER_ORDER_ID))
                .log("shipping saga started for orderId=${header.orderId}")
                .to("direct:ship-enrich")
                .to("direct:ship-dispatch")
                .to("direct:ship-book-carrier")
                .to("direct:ship-emit-dispatched")
                .end();

        from("direct:ship-enrich")
                .routeId("ship-enrich")
                .bean(steps, "enrich");

        from("direct:ship-dispatch")
                .routeId("ship-dispatch")
                .bean(steps, "dispatchShipment");

        from("direct:ship-book-carrier")
                .routeId("ship-book-carrier")
                .bean(steps, "bookCarrier");

        from("direct:ship-emit-dispatched")
                .routeId("ship-emit-dispatched")
                .bean(steps, "emitDispatched");

        // The compensation route -- a standalone direct: endpoint, invoked
        // by the saga coordinator itself (NOT chained from direct:ship-start
        // above), on any abort/timeout. DRQ-059.
        from("direct:ship-compensate")
                .routeId("ship-compensate")
                .log("shipping saga compensating for orderId=${header.orderId}")
                .bean(steps, "compensate");
    }
}
