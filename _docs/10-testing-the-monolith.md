---
title: "Testing the Monolith (the Equivalence Suite)"
order: 10
part: "The Reference Monolith"
description: "A three-tier test pyramid plus a Newman collection captured before any extraction, which becomes the behavior-equivalence suite for every later cut."
---

## Learning objectives

By the end of this chapter you can:

- Say which question each pyramid tier answers (Mockito unit, `@WebMvcTest` slice, Testcontainers integration) that the others can't.
- Split fast and slow tests with Surefire and Failsafe by filename alone, and name where that convention is fragile.
- Define characterization (golden-master) testing and explain why the equivalence suite is captured against the unmodified monolith first.
- Use a `baseUrl` variable and captured "before" values so one unedited Newman collection runs against the monolith and an extracted Quarkus service.
- Explain how Testcontainers finds a Docker Engine with no configuration, and the Ryuk gotcha that makes a run hang.
- Say why a green equivalence suite is necessary evidence but not sufficient evidence.

## Why this matters

The monolith in `examples/00-monolith/` carries planted smells: the god service, the shared schema, one sprawling transaction. Curing a smell is only safe if you can prove the cure didn't change what the system does. That takes two kinds of test. The first is the ordinary Spring Boot pyramid: unit tests for logic, slice tests for the HTTP layer, integration tests for the real database. The second is a suite that watches the monolith from outside, through its REST surface only, captured before a single line is extracted. That is the **behavior-equivalence suite**, and every extraction has to pass it unchanged.

The pyramid lives in `examples/00-monolith/src/test/` on the `reference/monolith-before` branch, where all six contexts are still in the monolith; on `main` the extractions have deleted the context packages and their tests, leaving only the smoke test. The equivalence suite lives in `tooling/newman/` and the run script in `demos/`, both present from the `stage/01-review-extracted` tag onward.

## The pyramid

Thirteen test classes make up the pyramid: four unit classes for the services with real branching (inventory, order, payment, review; shipping and notification have no branching to unit-test), six `@WebMvcTest` slices, one per REST controller, and three integration classes (two `@DataJpaTest` repository tests and a full-stack smoke test). That is forty-one `@Test` methods plus one `@ParameterizedTest` that expands to four executions: forty-five executions, all green on a clean `mvn verify`. Enough coverage per context to make the smells demonstrable, small enough to read in one sitting.

{% include excalidraw.html file="test-pyramid" alt="The monolith's three-tier test pyramid: Tier 1 unit tests (4 classes, Mockito, every collaborator mocked), Tier 2 slice tests (6 classes, @WebMvcTest, service mocked), Tier 3 integration tests (3 classes, real Postgres via Testcontainers). 13 classes, 45 executions, all green on mvn verify." caption="Figure 10.1: The monolith's test pyramid: three tiers, thirteen classes, forty-five executions" %}

| Tier | Question | Boots |
|---|---|---|
| 1, unit | Does this class's logic do the right thing? | Nothing: every collaborator is a Mockito mock |
| 2, slice | Does this controller bind, validate and serialize correctly? | Web stack only, service mocked |
| 3, integration | Does it work against real Postgres, Hibernate mappings and a Flyway schema? | Real database in a container |

Surefire's default pattern, `**/*Test.java`, runs Tier 1 and Tier 2 in the `test` phase. Failsafe, bound to `integration-test` and `verify`, picks up `*IT.java`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

`mvn test` runs the fast tiers and `mvn verify` runs all three. The split is driven by a filename suffix and nothing inspects what a test does. `SixContextsSmokeTest` boots a full Spring context against a throwaway Postgres, which is integration work, but its name ends in `Test`, so it runs in the fast phase. It still fails the build when red. A new integration-style test named `*Test` by habit gets the same treatment and Maven never complains.

## Tier 1: Mockito service tests

`OrderService` gets the deepest unit coverage because it is the god service and the single in-process transaction: it touches five of the six bounded contexts in one call. The tests cover the happy path, out-of-stock, payment-declined, customer-not-found and a read-side not-found. The payment-declined case proves ordering, not just an exception:

```java
@Test
void placeOrder_paymentDeclined_throwsAfterInventoryReservedButBeforeShippingOrNotification() {
    var command = new OrderCreate(
            1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 1)), "CARD-DECLINE", "1 Test Way");

    when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
    when(inventoryService.findBySkuOrThrow("SKU-WIDGET-001")).thenReturn(widget);
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new PaymentDeclinedException("Payment method 'CARD-DECLINE' was declined"))
            .when(paymentService).charge(any(Order.class), anyLong(), eq("CARD-DECLINE"));

    assertThatThrownBy(() -> orderService.placeOrder(command))
            .isInstanceOf(PaymentDeclinedException.class)
            .hasMessageContaining("declined");

    // SMELL[ch.22]: inventory WAS reserved (in-memory) before the decline; in
    // the real flow only the surrounding @Transactional rolls that back. This
    // unit test proves the orchestration order, not the rollback itself; the
    // rollback is exercised by the Testcontainers integration tier.
    verify(inventoryService).reserve("SKU-WIDGET-001", 1);
    verify(shippingService, never()).dispatch(any(), anyString());
    verify(notificationService, never()).sendOrderConfirmation(any(), any());
}
```

A unit test can say what happened before the exception and what didn't happen after it. Whether the reservation is rolled back in the database takes a real transaction. Tier 1 proves sequence and collaboration, Tier 3 proves durability. Inventory's `reserve` gets the same treatment for its own branches: sufficient stock decrements in place, insufficient stock throws and leaves the quantity untouched, and an unknown SKU throws a different exception. The payment decline rule (any method containing "decline", case-insensitively) is covered by one parameterized test over four variants (`CARD-DECLINE`, `card-decline`, `DECLINE-ANYTHING`, `visa-Decline-test`) instead of four near-copies.

## Tier 2: `@WebMvcTest` slices

A unit test says nothing about whether the HTTP layer calls the service correctly: whether a malformed body is rejected, a `201` carries its `Location` header, or a domain exception becomes the right status. `@WebMvcTest` boots only the web machinery around one controller, and `@MockitoBean` swaps in a mock service. `@AutoConfigureMockMvc(addFilters = false)` turns off the security filter chain, because order endpoints carry no authentication rule of their own; the authenticated rule belongs to Review.

```java
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private OrderService orderService;

    @Test
    void placeOrder_validCommand_returns201WithLocation() throws Exception {
        var command = new OrderCreate(
                1L, List.of(new OrderCreate.Line("SKU-WIDGET-001", 2)), "CARD-VISA", "1 Test Way");
        var dto = new OrderDto(
                7L, 1L, OrderStatus.CONFIRMED, 3998L, Instant.parse("2026-01-07T12:00:00Z"),
                List.of(new OrderDto.Item("SKU-WIDGET-001", 2, 1999L)));
        when(orderService.placeOrder(any(OrderCreate.class))).thenReturn(dto);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/orders/7"))
                .andExpect(jsonPath("$.status", is("CONFIRMED")))
                .andExpect(jsonPath("$.totalCents", is(3998)));
    }
}
```

The same class checks that an empty items list returns `400` with a `VALIDATION_FAILED` body and that an unknown order id returns `404` with `NOT_FOUND`. Error mapping lives entirely in the web layer, invisible to a unit test that never serializes anything.

Review's write-path authentication was tested the same way, with filters on, before that code left the monolith. The contract lives on in the equivalence suite's "Review Context Contract" folder (unauthenticated `POST` returns `401`, authenticated `POST` returns `201`), run against `examples/02-review-service`.

## Tier 3: `@DataJpaTest` and a real Postgres

Neither earlier tier proves that `Order`'s JPA mappings resolve against Postgres or that a cascade persists child rows. `OrderRepositoryIT` does:

```java
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("monolith").withUsername("monolith").withPassword("monolith");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void findById_seedOrder_resolvesCrossContextJoinsToCustomerAndInventoryItem() {
        Order order = orderRepository.findById(1L).orElseThrow();
        assertThat(order.getCustomer().getName()).isEqualTo("Ada Lovelace");       // SMELL[ch.18]
        assertThat(order.getItems().get(0).getInventoryItem().getSku())           // SMELL[ch.18]
                .isEqualTo("SKU-WIDGET-001");
    }
}
```

`Replace.NONE` makes this a Tier 3 class. `@DataJpaTest` defaults to an in-memory database; `Replace.NONE` turns that off so the `spring.datasource.*` properties that `@DynamicPropertySource` points at the container are used. Without it the class would quietly run against H2 and prove nothing about Postgres. The two `SMELL[ch.18]` assertions pin down the foreign-key joins from `orders` into the shared `customers` and `inventory_items` tables, the coupling the shared-schema smell names. That test has to be rewritten the day the coupling is cured. `InventoryRepositoryIT` covers the `PESSIMISTIC_WRITE` lock query behind `InventoryService#reserve`, which Spring Data's method-name parser compiles happily against a mock but only a real lock manager can validate.

Each repository test class starts its own `PostgreSQLContainer`. A static container shared across two top-level classes is torn down by the first class's `afterAll` and breaks the second. A private container costs a few seconds of startup per class. Sharing costs a flaky, order-dependent build. `SixContextsSmokeTest` applies the same rule to the whole app: `@SpringBootTest(webEnvironment = RANDOM_PORT)` against its own Postgres, one request per surviving context, plus a review check. On `reference/monolith-before` that check exercises Review's reads and its authenticated write through the shared security chain; after the Review extraction it asserts that `/api/reviews` returns `404`, confirming Review's controller, service, repository and entity left cleanly and nothing in the other five contexts depended on them.

### How Testcontainers finds Docker

No test configures a container runtime. Testcontainers looks for a reachable Docker Engine API in order: `DOCKER_HOST`, then `~/.testcontainers.properties`, then well-known socket paths. With Docker Engine installed as `docker-ce` and your user in the `docker` group, `/var/run/docker.sock` answers and no export is needed; no test code knows which engine answered. Nobody runs `docker compose up` before `mvn verify`, and the database is rebuilt on every run.

The Ryuk resource reaper, which cleans up leaked containers after a crashed JVM, must reach the same socket. A non-default Docker context or a stray `DOCKER_HOST` is the usual cause of a hanging run. `docker context use default` is the usual fix.

## Pattern: Characterization Testing

Everything above is white-box: it calls the monolith's classes or inspects its database. That proves today's implementation. It can't prove that a different implementation, a Quarkus service with its own schema and persistence, behaves the same, because a white-box test won't compile against code with different class names.

**Characterization testing** (golden master) pins down what a system does, observed from outside, before you change how it does it. The equivalence suite is written and run green against the unmodified monolith first. It records the real, current, possibly smelly behavior, not what a specification says or a cleaner rewrite would do. If the decline path rolls back a reservation inside the same ACID transaction as the order write, the collection captures that. Written after the fact, or against the new service, it has nothing to compare against, and a regression looks identical to a planned improvement.

The collection is `tooling/newman/mea.postman_collection.json`, run with the `newman` CLI. Every request targets a `baseUrl` collection variable, so the same collection points at the monolith on one run and at an extracted Quarkus service on the next. The **equivalence gate** is the rule built on it: an extraction is done only when this unchanged collection passes against it, per extraction and in CI. Four scenario folders carry the contract:

- **Smoke:** the target is reachable and serves seeded inventory.
- **Happy-path checkout:** `201 Created`, correct total and line items, a `Location` header, and stock reduced by the ordered quantity.
- **Out of stock:** `409 Conflict` with an `OUT_OF_STOCK` error body and unchanged stock.
- **Payment declined:** `402 Payment Required` with unchanged stock, which proves the rollback that the unit tier can't.

A fifth folder, "Review Context Contract," specifies Review's REST surface against the monolith's existing `/api/reviews` endpoints before that code moves.

{% include excalidraw.html file="whitebox-pyramid-vs-blackbox-equivalence" alt="Left, white-box: the Tier 1 through 3 pyramid calling monolith internals directly and unable to compile against a different implementation. Right, black-box: the Newman suite, which sees only HTTP and targets a baseUrl variable, so one unedited collection points at the monolith or an extracted Quarkus service." caption="Figure 10.2: White-box pyramid versus black-box equivalence suite: same system, two vantage points" %}

The same payment-decline behavior, proven from each vantage point:

{% include codetabs.html langs="Mockito (Tier 1 unit)|Newman (behavior-equivalence suite)" %}

```java
// OrderServiceTest: white-box. Calls Java directly, every collaborator mocked.
doThrow(new PaymentDeclinedException("Payment method 'CARD-DECLINE' was declined"))
        .when(paymentService).charge(any(Order.class), anyLong(), eq("CARD-DECLINE"));

assertThatThrownBy(() -> orderService.placeOrder(command))
        .isInstanceOf(PaymentDeclinedException.class)
        .hasMessageContaining("declined");

verify(inventoryService).reserve("SKU-WIDGET-001", 1); // reserved...
verify(shippingService, never()).dispatch(any(), anyString()); // ...then stopped
```

```javascript
// mea.postman_collection.json: black-box. A real POST; only the HTTP
// response and a captured collection variable are visible.
pm.test('Declined payment returns 402 Payment Required', function () {
    pm.response.to.have.status(402);
});
var json = pm.response.json();
pm.test('Documented error contract: error=PAYMENT_DECLINED', function () {
    pm.expect(json.error).to.eql('PAYMENT_DECLINED');
});
// ...a later request re-reads /api/inventory/{sku} and compares it to
// widgetStockBeforeDecline, a value this collection captured earlier,
// which shows the reservation rolled back without touching the DB.
```

The Mockito test sees that `shippingService.dispatch` was never called. The Newman assertion sees a status code, a JSON body and a second response it fetched itself. That restriction is the point: a Quarkus rewrite with a different call graph can still satisfy it. Capturing the "before" value as a collection variable, instead of hardcoding a stock number, lets the collection rerun against a long-lived service with mutating data and no database reset. Unattended CI depends on that.

## The equivalence gate in practice

The equivalence gate has a track record. During the Review extraction, sixteen assertions caught a native-image serialization defect that every JVM-mode run had missed. Forty-nine assertions, run through the strangler proxy with the monolith stopped, were the differential check that found a routing predicate silently sending every request to the wrong backend. Neither finding came from a new suite: both came from this collection, unchanged, run against a moving target.

The Review extraction is graded by the "Review Context Contract" folder. Each later extraction (Notification, Inventory, Payment, Shipping, and Order with its own gateway) must pass the same collection, extended with its own scenario folder and never rewritten for earlier services. A green suite is necessary evidence for every cutover. The routing bug shows it isn't sufficient, but it makes the search for what else is wrong possible, because it gives you something re-runnable to doubt.

> **Decision**
>
> Capture the black-box suite against the unmodified system before the first extraction, make the target a variable, and treat "passes the same unedited collection" as the definition of done. Keep white-box tests for logic and durability, and name integration tests `*IT`.

> **🤖 Explore with your agent**
>
> "On the `reference/monolith-before` branch, in examples/00-monolith, list every test class whose name ends in `Test` but starts a Testcontainers Postgres or a full Spring context. Rename each to `*IT`, run `mvn verify`, and report which phase now runs it. Then, on `stage/01-review-extracted`, add a scenario folder to tooling/newman/mea.postman_collection.json for an unknown-SKU checkout that uses the `baseUrl` variable and no hardcoded stock numbers."
>
> Check: the reply names `SixContextsSmokeTest`; after the rename `mvn verify` is green and Failsafe, not Surefire, lists it; the new folder contains no literal stock numbers.

## Build, run, observe

```bash
git checkout reference/monolith-before
cd examples/00-monolith && mvn verify
```

Surefire runs the Tier 1 and Tier 2 classes (and the smoke test despite its integration-grade setup). Failsafe then runs `OrderRepositoryIT` and `InventoryRepositoryIT`. `verify` fails if either phase is red. To run the equivalence suite against a live monolith, use the first ref that holds the collection:

```bash
git checkout stage/01-review-extracted

# 1. Postgres for the running app (separate from the throwaway Testcontainers instances)
docker compose --env-file .env up -d postgres

# 2. build and run the monolith; Flyway migrates and seeds automatically
cd examples/00-monolith && mvn -q -DskipTests package
SPRING_DATASOURCE_PASSWORD="$(grep ^POSTGRES_PASSWORD ../../.env | cut -d= -f2)" \
    java -jar target/monolith.jar

# 3. in another shell, from the project root
demos/demo-equivalence.sh
```

The collection was captured against the monolith on `http://localhost:8080`, Review included; that run is the baseline every extraction is measured against. At this tag Review's code has left the monolith, so the full collection runs through the strangler proxy with Review on `:8081` (`demos/demo-cutover.sh`): 49 of 49 assertions green, recorded in `examples/01-strangler-proxy/CUTOVER.md`.

- **Run `demos/demo-equivalence.sh` twice against the same target.** Both runs give the same result, because stock baselines are captured on each pass. Replace a captured baseline with a literal stock number and the second run fails.
- **Rename an `*IT` class to `*Test`.** It moves from Failsafe into the fast `test` phase and nothing complains.

The listings show the monolith as it stood on the `reference/monolith-before` branch: Spring Boot 3.5, Testcontainers 1.x and `postgres:16-alpine`. The frozen shell on `main` runs Spring Boot 4.1.1, where the same test reads `new PostgreSQLContainer("postgres:18.6-alpine")` from Testcontainers 2's `org.testcontainers.postgresql` package and takes `TestRestTemplate` from `spring-boot-resttestclient`. The shape of the pyramid is unchanged.

## For deeper coverage

- Sam Newman, *Monolith to Microservices* (O'Reilly, 2019): capturing a system's actual behavior before any code moves; see the [Reading List](/bibliography/).
- [Testcontainers: Java documentation](https://java.testcontainers.org/): container-runtime discovery and the Ryuk resource reaper.
- [Newman: command-line collection runner](https://github.com/postmanlabs/newman): the tool that runs the equivalence suite.
- Chris Richardson, *Microservices Patterns* (Manning, 2018): the bar an extracted service has to clear, measured the same black-box way.
- Martin Kleppmann, *Designing Data-Intensive Applications* (O'Reilly, 2017): the durability guarantees the Tier 3 tests pin down before the shift from ACID to ACD removes them.
