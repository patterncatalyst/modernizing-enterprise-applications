package dev.patterncatalyst.monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The reference monolith: one deployable, one shared Postgres schema, one JVM.
 *
 * <p>Originally six bounded contexts lived as sibling packages under this one
 * application: {@code order}, {@code inventory}, {@code payment},
 * {@code shipping}, {@code notification}, {@code review}. This is the
 * believable "before" picture that the sibling projects (DataMesh-Quarkus,
 * EIP-Camel, DDD-Obs) all converge on as their "after" — see
 * {@code _plans/research/reuse-map.md} section 6.
 *
 * <p><b>r02/S10 update:</b> {@code review} was extracted and decommissioned
 * from this module — served exclusively by the standalone
 * {@code examples/02-review-service} Quarkus application, reached through the
 * Camel strangler proxy ({@code examples/01-strangler-proxy}) with
 * {@code strangler.review.enabled=true} as the permanent default. Review's
 * REST-leaf independence (no synchronous collaborator with the other five)
 * is exactly what made that extraction clean — see {@code SMELLS.md} smell #6.
 *
 * <p><b>r04/S8 update:</b> {@code notification} was likewise extracted and
 * decommissioned — served exclusively by the standalone
 * {@code examples/03-notification-service} Quarkus application (consuming
 * {@code order.placed} off Kafka), reached through the same strangler proxy
 * with {@code strangler.notification.enabled=true} as the permanent default.
 *
 * <p><b>r05/ch.19 S11, r06/ch.23 S9, r07/ch.24 S9 updates:</b> {@code
 * inventory}, {@code payment}, and {@code shipping} were extracted and
 * decommissioned in turn, each time leaving only {@code order} as the one
 * context still fully owned in-process.
 *
 * <p><b>order-plan.md S10 update (DRQ-070) — THE FINAL DECOMMISSION:</b>
 * {@code order} — the last of the six contexts, the god {@code OrderService}/
 * {@code OrderSagaListener} hub (SMELL #2) plus the whole {@code common.*}
 * vocabulary (DTOs/events/exceptions), the transactional-outbox relay
 * ({@code common.outbox.*}), and the gRPC inventory client
 * ({@code inventory.RemoteInventoryClient}) — has now also been extracted
 * (to {@code examples/07-order-service}) and decommissioned. This module owns
 * ZERO live bounded contexts and serves nothing under {@code /api/**} — see
 * {@code SixContextsSmokeTest} (now a pure boot-and-404 smoke test) and
 * {@code SMELLS.md} (#2 and #3 now fully cured, ACID→ACD realized for ALL
 * contexts). {@code @EnableScheduling} has been removed: the only
 * {@code @Scheduled} bean this module ever had ({@code OutboxRelay}) is gone
 * with it. Per DRQ-070/DRQ-024 this module is KEPT, frozen, as the
 * permanent "before" + golden-baseline referent — removed from the running
 * topology (`compose.yaml`) but still built and tested by CI as a shell. The
 * complete, runnable "before" (all six contexts still live) is preserved on
 * the {@code reference/monolith-before} branch (tag {@code v0-monolith});
 * {@code stage/NN-*-extracted} tags step through each extraction in order.
 *
 * <p>Six deliberate smells were planted on purpose and tagged in-code with
 * {@code // SMELL[ch.NN]: ...} comments; see {@code SMELLS.md} in this module's
 * root for the full catalogue and curing-chapter map — all six are now cured
 * (SMELL #2 and the last clause of #3 by this very update; #1/#4/#5/#6 in
 * earlier decommissions). A handful of {@code SMELL[ch.NN]} tags remain
 * visible in surviving, deliberately-vacuous or historical files (the
 * Flyway migrations, {@code SecurityConfig}'s now-vacuous Review rule, and
 * this test's own commentary) — their survival as plain comments, with no
 * live code left to exercise what they describe, is itself evidence of the
 * cure, not a contradiction of it; SMELLS.md records the full history and
 * evidence per smell.
 */
@SpringBootApplication
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
