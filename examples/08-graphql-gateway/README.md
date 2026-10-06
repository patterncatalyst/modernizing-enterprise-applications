# graphql-gateway

**ch.26 S7 (DRQ-069) — a new, additive, data-less SmallRye GraphQL
aggregation gateway.** Adapted from
`~/Dev/datamesh-reference-arch-quarkus/examples/graphql-gateway` (DRQ-032)
with attribution, widened from order+stock to the full cross-context view:
`order → payments/shipments/reviews/stock` across the five already-extracted
services.

**This module OWNS NO DATA.** It answers one GraphQL query by federating
reads from order-service (REST), payment-service (REST), shipping-service
(REST), review-service (REST), and inventory-service (gRPC) — each field is
resolved by a `@Source` field resolver calling the service that owns it, the
same protocol that service's other consumers use. It sits ALONGSIDE the REST
surface and the strangler proxy, not replacing either — its own front door,
port `8090`.

- `type Query { order(id: ID!): OrderView }` — `OrderView` carries the order
  read-model fields (`id`, `customerId`, `status`, `totalCents`,
  `createdAt`, `shippingAddress`) plus `items: [OrderItemView]`, `payments:
  [PaymentView]`, `shipments: [ShipmentView]`. Each `OrderItemView` carries
  its own `stock: StockView` (inventory-service, gRPC `GetStock`) and
  `reviews: [ReviewView]` (review-service, REST, by sku).
- `GatewayApi` (`@GraphQLApi`) is the only resolver class: `order(id)` calls
  `OrderRestClient`; `payments`/`shipments` are `@Source` resolvers on
  `OrderView`; `stock`/`reviews` are `@Source` resolvers on `OrderItemView`.
- **Unknown-id handling:** `order(id:)` for an id order-service 404s on
  raises `OrderNotFoundException` (`@ErrorCode("ORDER_NOT_FOUND")`) from the
  resolver rather than returning `null` directly — the GraphQL response is
  `data.order: null` WITH a populated `errors[]`, never a silent null with
  no error (the GraphQL Gateway Contract's GG-b envelope).
- **Secure-by-design (DRQ-069):** query depth/complexity are bounded
  (`smallrye.graphql.instrumentation.queryDepth`/`queryComplexity` in
  `application.properties`) so the aggregation can't be turned into an
  amplification vector, and every downstream target (the four REST client
  base URLs, the inventory gRPC host/port) is FIXED operator config —
  never derived from the incoming request.
- `src/main/proto` carries a verbatim copy of inventory-service's
  `.proto` (the same `GetStock` RPC `examples/07-order-service`'s
  `RemoteInventoryClient` dials).

## Endpoints

- `POST /graphql` — the aggregation query.
- `GET /graphql/schema.graphql` — the generated SDL.
- `/q/graphql-ui` — GraphiQL (dev mode).
- `/q/health`, `/q/health/live`, `/q/health/ready`.

## Configuration

Downstream targets (all overridable via env var, see
`application.properties`): `ORDER_SERVICE_URL` (default
`http://localhost:8087`), `PAYMENT_SERVICE_URL` (`http://localhost:8085`),
`SHIPPING_SERVICE_URL` (`http://localhost:8088`), `REVIEW_SERVICE_URL`
(`http://localhost:8081`), `INVENTORY_GRPC_HOST`/`INVENTORY_GRPC_PORT`
(`localhost`/`9004`).

Port `8090` (test port `8092`); the vestigial gRPC *server* quarkus-grpc
always starts (this module has no `@GrpcService` of its own in `main` — only
a `@GrpcClient`) is pinned to `9093` to avoid colliding with the extension's
default `9000`.

## Testing

`GatewayApiTest` (`@QuarkusTest`) mocks the four REST downstreams with
`@InjectMock @RestClient` and serves inventory-service's gRPC `GetStock` from
an in-process `@GrpcService` test double (`MockInventoryGrpcService` — the
same pattern datamesh's `MockInventoryService` uses, since a `@GrpcClient`
blocking stub is a `@Singleton` and can't be `@InjectMock`-ed):

- `aggregatesOrderAcrossAllFiveServices` — the GG-a shape, field-for-field.
- `unknownOrderIdProducesErrorEnvelope` — the GG-b error envelope.
- `downstreamUnavailableProducesPartialDataNotACrash` — one failing
  downstream nulls only its own field; siblings still resolve, no 500.

`GatewayDepthComplexityLimitTest` (`@TestProfile(StrictGraphQLLimitsProfile)`)
proves the depth/complexity bound is actually wired and active, under a
deliberately tiny test-only budget.

## Related Guides

- SmallRye GraphQL ([guide](https://quarkus.io/guides/smallrye-graphql)): Create GraphQL Endpoints using the code-first approach from MicroProfile GraphQL
- REST Client ([guide](https://quarkus.io/guides/rest-client)): Type-safe HTTP client for consuming REST APIs
- gRPC ([guide](https://quarkus.io/guides/grpc)): Federate inventory-service's `GetStock` RPC
- SmallRye Health ([guide](https://quarkus.io/guides/smallrye-health)): Monitor service health

## Packaging and running

```shell script
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

## Dev mode

```shell script
./mvnw quarkus:dev
```
