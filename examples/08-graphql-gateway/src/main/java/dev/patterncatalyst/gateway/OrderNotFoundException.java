package dev.patterncatalyst.gateway;

import io.smallrye.graphql.api.ErrorCode;

/**
 * Thrown by {@link GatewayApi#order} when order-service answers 404 for the
 * requested id. This is the GraphQL Gateway Contract's GG-b negative check
 * (ch.26 S7, DRQ-069): {@code order(id:)} for an unknown id must raise a
 * resolver-level ERROR -- producing a {@code data.order: null} response WITH
 * a populated {@code errors[]} array -- not a silently-null field with no
 * error. Returning {@code null} from the resolver instead of throwing would
 * satisfy "data.order is null" but NOT "errors[] is populated", which is
 * exactly the silent-null failure mode GG-b exists to catch.
 *
 * <p>{@code @ErrorCode} tags the GraphQL error's {@code extensions.code}
 * with a stable, documented value a client can branch on (distinct from
 * {@code extensions.classification}, which SmallRye GraphQL sets to
 * {@code DataFetchingException} for any resolver-thrown exception).
 * {@code quarkus.smallrye-graphql.show-runtime-exception-message} in
 * application.properties whitelists THIS exception's message to be shown
 * verbatim (it carries no internal detail, just "order &lt;id&gt; not
 * found") -- every other unexpected exception stays masked behind a generic
 * "Server Error" message, the secure default (never echo an arbitrary
 * internal exception's message to a GraphQL client).
 */
@ErrorCode("ORDER_NOT_FOUND")
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
