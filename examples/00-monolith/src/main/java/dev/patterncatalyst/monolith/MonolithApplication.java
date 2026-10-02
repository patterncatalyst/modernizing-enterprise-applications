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
 * <p><b>r02/S10 update:</b> {@code review} has been extracted and decommissioned
 * from this module — it is now served exclusively by the standalone
 * {@code examples/02-review-service} Quarkus application, reached through the
 * Camel strangler proxy ({@code examples/01-strangler-proxy}) with
 * {@code strangler.review.enabled=true} as the permanent default. This
 * monolith now owns FIVE in-process contexts (order, inventory, payment,
 * shipping, notification); Review's REST-leaf independence (no synchronous
 * collaborator with the other five) is exactly what made that extraction
 * clean — see {@code SMELLS.md} smell #6.
 *
 * <p>Six deliberate smells were planted on purpose and tagged in-code with
 * {@code // SMELL[ch.NN]: ...} comments; see {@code SMELLS.md} in this module's
 * root for the full catalogue and curing-chapter map (smell #6 is now cured).
 */
@SpringBootApplication
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
