package dev.patterncatalyst.monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The reference monolith: one deployable, one shared Postgres schema, one JVM.
 *
 * <p>Six bounded contexts live as sibling packages under this one application:
 * {@code order}, {@code inventory}, {@code payment}, {@code shipping},
 * {@code notification}, {@code review}. This is the believable "before" picture
 * that the sibling projects (DataMesh-Quarkus, EIP-Camel, DDD-Obs) all converge
 * on as their "after" — see {@code _plans/research/reuse-map.md} section 6.
 *
 * <p>Six deliberate smells are planted on purpose and tagged in-code with
 * {@code // SMELL[ch.NN]: ...} comments; see {@code SMELLS.md} in this module's
 * root for the full catalogue and curing-chapter map.
 */
@SpringBootApplication
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
