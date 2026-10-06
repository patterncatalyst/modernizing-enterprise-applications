# The Strangler-Fig Proxy

This is the **Camel strangler-fig proxy** (ch.14 "The Strangler Fig Pattern",
r02-plan step S7, build-plan.md §E step 0) — a standalone Camel-on-Quarkus
application that sits in front of the Spring Boot monolith
(`examples/00-monolith/`) so that bounded contexts can be peeled off onto
Quarkus **one seam at a time**, with clients none the wiser about which
backend actually served a given request.

## The pattern

Martin Fowler's strangler-fig application grows a new system up around the
edges of an old one until the old one can be removed. The mechanical piece
that makes this safe is a **facade in front of both systems** that decides,
per request, which one answers. This project *is* that facade for Review, the
first extraction in the decomposition roadmap (build-plan.md §E):

```
            ┌──────────────────┐
 client --> │  strangler proxy │ --> monolith            (default, r02)
            │     :8888        │ --> review-service :8081 (once cut over, S10)
            └──────────────────┘
```

Every request to the proxy is reverse-proxied **as-is**: same HTTP method,
path, query string, headers, and body go out; the backend's actual status
code and body come straight back. The proxy introduces zero observable
difference versus talking to a backend directly — that transparency is the
whole point, and it's what the behavior-equivalence suite proves (see
"Verifying transparency" below).

## The route

One route, one `RouteBuilder`
(`src/main/java/dev/patterncatalyst/strangler/StranglerProxyRoute.java`):

1. **Consume** `platform-http:/api?matchOnUriPrefix=true` — every request
   under `/api/**` lands here.
2. **Content-based routing on the Review seam** — a `choice()` checks whether
   the request path starts with `/reviews` *and* the cutover flag is on; every
   other path (and `/reviews` with the flag off) is routed to the monolith.
3. **Reverse-proxy** to whichever backend was chosen, via the Camel `http`
   producer with `bridgeEndpoint=true` (reuse the inbound method/path/query
   as-is) and `throwExceptionOnFailure=false` (pass the backend's real status
   code and body back instead of raising a Camel exception on 4xx/5xx).

## The flag: `strangler.review.enabled`

| Value | Backend for `/api/reviews/**` | Backend for everything else |
|---|---|---|
| `false` (r02/S7–S9) | monolith `:8080` | monolith `:8080` |
| `true` (**default**, from r02/S10 — permanent) | Review service `:8081` | monolith `:8080` |

Set in `src/main/resources/application.properties`, or overridden at runtime
with `-Dstrangler.review.enabled=true` / `STRANGLER_REVIEW_ENABLED=true`. It
is read once per request from Quarkus/SmallRye Config — flipping it is a
config change plus a restart, not a code change, which is exactly what made
the cutover (and, while the monolith's Review module still existed, rolling it
back) a *reversible* operation rather than a rewrite.

**r02/S10 — the cutover is now permanent.** `strangler.review.enabled=true` is
the committed default and the monolith's Review module (controller, service,
repository, entity, DTOs) has been decommissioned — removed from
`examples/00-monolith/` entirely (see its `SMELLS.md`, smell #6, now cured).
Before that decommission, both flag states were proven green against the full
behavior-equivalence suite (49/49 assertions each), demonstrating real
reversibility right up until the one deliberately irreversible step. See
`CUTOVER.md` in this directory for the full before/after/decommission trace.

## The flag: `strangler.notification.enabled` (notification-plan.md S6/S7, ch.17)

| Value | Backend for `/api/notifications/**` | Backend for everything else |
|---|---|---|
| `false` (notification-plan S6) | monolith `:8080` (synchronous read surface) | unaffected |
| `true` (**default**, from notification-plan S7 — cutover) | notification-service `:8083` (async, outbox -> Kafka -> consumer) | unaffected |

Identical shape to the Review flag — content-based routing on the
`/api/notifications` path prefix, matching the **full** incoming path (the
exact bug CUTOVER.md §2 documents for Review: matching a route-relative
`/notifications` prefix instead would silently never match, since
`platform-http`'s `CamelHttpPath` always carries the full path). This is the
**read-side** half of a two-flag reversibility story (DRQ-036): the
monolith's own `notification.mode=synchronous|outbox` config (a *different*
file, `examples/00-monolith/src/main/resources/application.yml`) is the
write-side half. A real cutover flips both together.

**Cutover evidence (notification-plan S7):** with both flags flipped
(monolith `NOTIFICATION_MODE=outbox` + this flag `true`), the full
behavior-equivalence suite ran green through this proxy, and critically the
"Notification Context Contract" folder's bounded-wait poll *actually
retried* several times before the notification became observable — real
evidence of outbox -> Kafka -> consumer latency, not an instant synchronous
hit. Reversibility was demonstrated first (flag `false` + monolith
synchronous, full suite green). A **negative check** — stopping the
notification-service process (its consumer and its read surface) and
re-running just that folder — went RED (the proxy's forwarded 500 /
connection failure broke the "returns 200" and JSON-body assertions), then
GREEN again once the service was restarted — proving the assertion genuinely
exercises the async pipeline rather than passing for the wrong reason
(DRQ-037, guarding against the exact false-equivalence trap CUTOVER.md §2
describes for Review). See "Notification cutover" in `CUTOVER.md` for the
full run-by-run trace.

Unlike Review's flip, this is **not** the point where reversibility closes:
the monolith's synchronous notification path and `/api/notifications` read
surface still exist (decommission is notification-plan S8, not yet run) —
flipping this flag back to `false` today still reaches a working monolith
notification surface.

## The flag: `strangler.inventory.enabled` (inventory-plan.md S9, ch.19, DRQ-045)

| Value | Backend for `/api/inventory/**` | Backend for everything else |
|---|---|---|
| `false` (**default**, inventory-plan S9 — not yet cut over) | monolith `:8080` | unaffected |
| `true` (inventory-plan S10 — cutover) | inventory-service `:8084` | unaffected |

Identical shape to the Review and Notification flags — content-based routing
on the `/api/inventory` path prefix, matching the **full** incoming path (the
same CUTOVER.md §2 lesson applied a third time). This is the **read-side**
half of a two-flag reversibility story (DRQ-045): the monolith's own
`inventory.mode=local|remote` config (a *different* file,
`examples/00-monolith/src/main/resources/application.yml`) is the
write/reserve-side half — it governs whether `OrderService#placeOrder`
reserves stock in-JVM against the shared schema, or over gRPC against
`examples/04-inventory-service/`. A real cutover (inventory-plan S10) flips
both together.

### ACL honesty: why this branch is a transparent proxy, not a translator

Chapter 16 sketched `InventoryAclRoute` — a content-based router feeding an
`enrich()` + `AggregationStrategy` message translator — as the shape this seam
would eventually need. This route does **not** build that translator, and
that is a deliberate, documented choice rather than a shortcut:

- The monolith's `/api/inventory` read surface returns
  `dev.patterncatalyst.monolith.common.StockDto` (`sku`, `name`, `priceCents`,
  `quantityOnHand`).
- The inventory service's `/api/inventory` read surface returns
  `dev.patterncatalyst.inventory.StockDto` — lifted **unchanged** from the
  monolith's record (same field names, types, and order; see its javadoc).
- Both backends therefore produce **byte-for-byte identical JSON** for this
  read surface. A Camel message translator here would have nothing to
  translate — adding one would fabricate a redundant ACL for a contract that
  does not actually differ, which is exactly the kind of speculative
  infrastructure this project's scope discipline rules out. So the
  `/api/inventory` branch above is an honest, transparent reverse proxy,
  structurally identical to the Review and Notification branches.

**The real anti-corruption layer for the order→inventory seam is realized at
the gRPC boundary, not in this proxy.** `inventory.proto`
(`examples/00-monolith/src/main/proto/.../inventory.proto`, mirrored in
`examples/04-inventory-service/src/main/proto/.../inventory.proto`) defines a
wire vocabulary that **does** genuinely differ from the internal model:
`stock_keeping_unit` / `unit_price_cents` / `on_hand_qty` on the wire versus
`sku` / `priceCents` / `quantityOnHand` internally. Two classes do the actual
translation work ch.16 described:

- `examples/00-monolith/src/main/java/dev/patterncatalyst/monolith/inventory/RemoteInventoryClient.java`
  — the **client-side** translator: converts the gRPC reply vocabulary into
  plain internal values the rest of the monolith understands. The generated
  proto types never leak past this class.
- `examples/04-inventory-service/src/main/java/dev/patterncatalyst/inventory/InventoryGrpcServiceImpl.java`
  — the **server-side** translator: converts the inventory service's internal
  `InventoryItem`/`StockDto` shape into the proto `StockReply`/`ReserveReply`
  wire vocabulary.

This is ch.16's `InventoryAclRoute` sketch, realized for real — just one layer
over from where the sketch originally proposed it, because that is where the
seam's vocabulary actually diverges. Content-based routing (this proxy, the
"which door to knock on" decision) and the anti-corruption layer (the gRPC
translators, the "what's safe to carry back" decision) remain the two
independent concerns ch.16 named; they simply don't both have to live in the
same Camel route when one side of the seam hasn't diverged.

## The flag: `strangler.payment.enabled` (payment-plan.md S7, ch.23, DRQ-052)

| Value | Backend for `/api/payments/**` | Backend for everything else |
|---|---|---|
| `false` (**default**, payment-plan S7 — not yet cut over) | monolith `:8080` | unaffected |
| `true` (payment-plan S8 — cutover) | payment-service `:8085` | unaffected |

Identical shape to the Review, Notification, and Inventory flags — content-based
routing on the `/api/payments` path prefix, matching the **full** incoming path
(the same CUTOVER.md paragraph 2 lesson applied a fourth time).

### ACL honesty: why this branch is also a transparent proxy, not a translator

The payment-plan's S7 step title says "wire `PaymentAclRoute` (ACL
translator)" — but the same honesty check already applied to Inventory above
applies here, and the answer is the same:

- The monolith's `/api/payments` read surface returns
  `dev.patterncatalyst.monolith.payment.PaymentDto`: `record PaymentDto(Long
  id, Long orderId, long amountCents, String method, PaymentStatus status,
  Instant createdAt)`.
- The payment service's `/api/payments` read surface returns
  `dev.patterncatalyst.payment.PaymentDto` — lifted **unchanged**: `record
  PaymentDto(Long id, Long orderId, long amountCents, String method,
  PaymentStatus status, Instant createdAt)`. Same field names, types, and
  order; same `PaymentStatus` enum (`CAPTURED`, `DECLINED`); same paths
  (`GET /api/payments/{id}`, `GET /api/payments?orderId=`); same 404/400
  status codes.
- `orderId` was already a plain `Long` **on the wire** in the monolith — only
  the JPA entity (`monolith.payment.Payment`) carried a cross-context
  `@ManyToOne Order` join (`SMELL[ch.18]`). The payment service's `Payment`
  entity decomposes that FK to a plain `orderId` value column (r06/S4,
  DRQ-052 Phase A) — but that is an internal persistence-layer change, not a
  read-contract change, so `PaymentDto` needed zero edits.
- The `payment.captured`/`payment.declined` Kafka events the two sides
  exchange (DRQ-048) are likewise authored field-for-field identical on both
  sides per DRQ-038 (plain JSON, no shared code between the two Maven
  reactors).

Both backends therefore produce **byte-for-byte identical JSON** for this
read surface. A Camel message translator here would have nothing to
translate — building one anyway (as the plan step's literal title suggests)
would fabricate a redundant ACL for a contract that does not differ, exactly
the speculative-infrastructure trap the Inventory precedent above already
ruled out. So the `/api/payments` branch is an honest, transparent reverse
proxy, structurally identical to the Review, Notification, and Inventory
branches — there is no `PaymentAclRoute` class in this project.

Unlike Inventory's gRPC seam, there also isn't a *different* layer where a
genuine payment ACL lives: the payment service's choreography (consuming
`order.placed`, producing `payment.captured`/`payment.declined`) deliberately
authors the same field vocabulary on both sides (DRQ-038), so there is no
wire-format divergence anywhere in this seam for a translator to bridge —
not at this proxy, and not elsewhere. If that ever changes (e.g. a future
Avro/Apicurio migration, ch.28, introduces a schema divergence), that is
where a real translator would belong, same reasoning as Inventory's gRPC
write-up above.

## The flag: `strangler.shipping.enabled` (shipping-plan.md S7, ch.24, DRQ-065)

| Value | Backend for `/api/shipments/**` | Backend for everything else |
|---|---|---|
| `false` (**default**, shipping-plan S7 — not yet cut over) | monolith `:8080` | unaffected |
| `true` (shipping-plan S8 — cutover) | shipping-service `:8088` | unaffected |

Identical shape to the Review, Notification, Inventory, and Payment flags —
content-based routing on the `/api/shipments` path prefix, matching the
**full** incoming path (the same CUTOVER.md paragraph 2 lesson applied a
fifth time). Note the shipping service listens on **`:8088`**, not `:8086`
(Debezium Connect, an unrelated compose.yaml service).

### ACL honesty: why this branch is also a transparent proxy, not a translator

The same honesty check already applied to Inventory and Payment above applies
here too, and the answer is the same:

- The monolith's `/api/shipments` read surface returns
  `dev.patterncatalyst.monolith.shipping.ShipmentDto`: `record
  ShipmentDto(Long id, Long orderId, String address, ShipmentStatus status,
  Instant createdAt)`.
- The shipping service's `/api/shipments` read surface returns
  `dev.patterncatalyst.shipping.ShipmentDto` — lifted **unchanged**: `record
  ShipmentDto(Long id, Long orderId, String address, ShipmentStatus status,
  Instant createdAt)`. Same field names, types, and order; same paths
  (`GET /api/shipments/{id}`, `GET /api/shipments?orderId=`).
- The shipping service's `ShipmentStatus` enum adds `PENDING`/`CANCELLED`/
  `FAILED` (needed by the shipping-plan S5 saga's compensation leg) alongside
  the monolith's single `DISPATCHED` value, but `status` is still a plain
  JSON string on the wire, and `DISPATCHED` is the only value either side has
  ever produced so far — so the wire shape is unaffected.

Both backends therefore produce **byte-for-byte identical JSON** for this
read surface. A Camel message translator here would have nothing to
translate — building one anyway (as the shipping-plan's step title literally
suggests) would fabricate a redundant ACL for a contract that does not
differ, exactly the speculative-infrastructure trap the Inventory and Payment
precedents above already ruled out. So the `/api/shipments` branch is an
honest, transparent reverse proxy, structurally identical to the Review,
Notification, Inventory, and Payment branches — there is no
`ShippingAclRoute` class in this project.

## The flag: `strangler.order.enabled` (order-plan.md S8, ch.26, DRQ-065 precedent) — the sixth and LAST flag

| Value | Backend for `/api/orders/**` (POST + GET) | Backend for everything else |
|---|---|---|
| `false` (**default**, order-plan S8 — not yet cut over) | monolith `:8080` | unaffected |
| `true` (order-plan S9 — cutover) | order-service `:8087` | unaffected |

Identical shape to the Review, Notification, Inventory, Payment, and Shipping
flags — content-based routing on the `/api/orders` path prefix, matching the
**full** incoming path (the same CUTOVER.md paragraph 2 lesson applied a
sixth time). Unlike the five prior flags, this one routes **both** the POST
checkout command and the GET reads through a single branch — the whole order
bounded context (the god `OrderService`/`OrderSagaListener` hub, SMELL #2)
moves at once; there is no narrower read/write split worth staging at the
proxy layer (the CQRS write/read split, DRQ-067, happens inside the order
service itself). This is the last strangler flag in the book: order-plan S9
flips it (with reversibility demonstrated one final time), and order-plan S10
is the single, deliberately irreversible decommission for the whole system —
there is no seventh context to extract, and no monolith left to fall back to
afterward. Note the order service listens on **`:8087`**, not `:8086`
(Debezium Connect) and not `:8090` (the GraphQL gateway, order-plan.md
DRQ-069).

### ACL honesty: why this branch is also a transparent proxy, not a translator

The same honesty check already applied to Inventory, Payment, and Shipping
above applies here too, and the answer is the same:

- The monolith's `/api/orders` surface returns
  `dev.patterncatalyst.monolith.common.OrderDto`: `record OrderDto(Long id,
  Long customerId, OrderStatus status, long totalCents, Instant createdAt,
  String shippingAddress, List<Item> items)` with nested `record Item(String
  sku, int quantity, long unitPriceCents)`.
- The order service's `/api/orders` surface returns
  `dev.patterncatalyst.order.OrderDto` — lifted **unchanged** (order-plan
  S4/S5, DRQ-068/073 Phase A): the identical record, same field names, types,
  and order, same nested `Item` shape, same paths (`GET /api/orders/{id}`,
  `POST /api/orders`).
- `customerId` stays a plain `Long` on the wire on both sides. The FK
  decomposition (DRQ-068) changes only the order service's **internal** JPA
  mapping (no more `@ManyToOne Customer`) — it never reaches this external
  read contract.

Both backends therefore produce **byte-for-byte identical JSON**. A Camel
message translator here would have nothing to translate — building one
anyway would fabricate a redundant ACL for a contract that does not differ,
exactly the speculative-infrastructure trap the Inventory/Payment/Shipping
precedents above already ruled out. So the `/api/orders` branch is an
honest, transparent reverse proxy, structurally identical to the Review,
Notification, Inventory, Payment, and Shipping branches — there is no
`OrderAclRoute` class in this project.

## Backend targets

Properties name the fixed backend targets each flag chooses between. The
route never builds a target URI from request data — only ever from these
configured constants — which keeps the dynamic-URI seam secure by default (no
header or path value can redirect the proxy to an arbitrary host):

```properties
strangler.monolith.base-url=http://localhost:8080
strangler.review.base-url=http://localhost:8081
strangler.notification.base-url=http://localhost:8083
strangler.inventory.base-url=http://localhost:8084
strangler.payment.base-url=http://localhost:8085
strangler.shipping.base-url=http://localhost:8088
strangler.order.base-url=http://localhost:8087
```

## Ports

| Component | Port | Note |
|---|---|---|
| Strangler proxy (this project) | **8888** | `quarkus.http.port` |
| Monolith (`examples/00-monolith/`) | 8080 | default target, always |
| Review service (`examples/02-review-service/`) | 8081 | only reachable through the proxy once the Review flag flips (S10) |
| Notification service (`examples/03-notification-service/`) | 8083 | only reachable through the proxy once the Notification flag is on (notification-plan S7) |
| Inventory service (`examples/04-inventory-service/`) | 8084 | only reachable through the proxy once the Inventory flag is on (inventory-plan S10); gRPC server at :9004 is used by the monolith directly, not via this proxy |
| Payment service (`examples/05-payment-service/`) | 8085 | only reachable through the proxy once the Payment flag is on (payment-plan S8); not yet enabled by default (payment-plan S7) |
| Shipping service (`examples/06-shipping-service/`) | 8088 | only reachable through the proxy once the Shipping flag is on (shipping-plan S8); not yet enabled by default (shipping-plan S7). Note: `:8086` is Debezium Connect (compose.yaml), not this service. |
| Order service (`examples/07-order-service/`) | 8087 | only reachable through the proxy once the Order flag is on (order-plan S9); not yet enabled by default (order-plan S8). Note: `:8086` is Debezium Connect and `:8090` is the GraphQL gateway (order-plan.md DRQ-069) — neither is this service. |

## Running it

```bash
# 1. podman stack + monolith already up (see tooling/newman/README.md)
# 2. build and run the proxy
cd examples/01-strangler-proxy
mvn -q -DskipTests package
java -jar target/quarkus-app/quarkus-run.jar
# proxy now listening on :8888, flag defaults to false (-> monolith)
```

Or for the dev-mode inner loop: `quarkus dev` (live reload on route changes).

## Verifying transparency (the equivalence gate, through the proxy)

The project's behavior-equivalence suite
(`tooling/newman/mea.postman_collection.json`) is baseUrl-parameterized, so
the exact same 49 assertions pass **through this proxy** regardless of which
backend is actually answering `/api/reviews/**`:

```bash
demos/demo-equivalence.sh http://localhost:8888
```

This was run green three times across r02/S10 (see `CUTOVER.md` for the full
trace): once with the flag off (Review served by the monolith), once with the
flag on (Review served by `examples/02-review-service`, everything else still
the monolith) — proving reversibility — and once more after the monolith's
Review module was decommissioned (Review served by Quarkus, everything else
served by the now-slimmed, five-context monolith). All three runs: 49/49
assertions, 0 failed, with the collection completely unmodified between runs —
only `--baseUrl` and the proxy's flag changed.
