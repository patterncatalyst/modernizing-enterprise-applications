---
title: "Testing the Monolith (the Equivalence Suite)"
order: 10
part: "The Reference Monolith"
description: "Unit and Testcontainers integration tests plus the Newman contract collection that becomes the behavior-equivalence suite for every later extraction."
---

Chapter 9 catalogued the smells planted in `examples/00-monolith/` —
the god service, the shared schema, the one sprawling transaction. Planting a
smell only has value if you can prove, later, that curing it didn't
change what the system does. That proof needs two different kinds of test, and
this chapter builds both. The first kind is the ordinary three-tier pyramid
every Spring Boot codebase should have: unit tests for the logic, slice tests
for the HTTP layer, integration tests for the real database. The second kind
is new, and it is the one new idea this chapter exists to teach: a suite that
watches the monolith from outside, through its REST surface only, captured
*before* a single line is extracted — the **behavior-equivalence suite** that
Part 5 onward will re-run, unchanged, as the gate every extraction has to
clear.

The pyramid lives in `examples/00-monolith/src/test/`; the behavior-equivalence
suite lives in `tooling/newman/`. Both are already-committed code: this
chapter walks the code as it exists on disk.

## Three tiers, one `mvn verify`

Thirteen test classes carry the pyramid: four Tier 1 unit-test classes against
the four services with real branching logic — `InventoryServiceTest`,
`OrderServiceTest`, `PaymentServiceTest`, `ReviewServiceTest` (shipping and
notification have no branching logic to unit-test), six Tier 2 `@WebMvcTest`
slices against the six REST controllers, and a mixed Tier 3 of two
`@DataJpaTest` repository tests plus one full-stack smoke test. Counting
methods rather than classes: forty-one plain `@Test` methods, plus one
`@ParameterizedTest` in `PaymentServiceTest` that expands into four more
executions at run time — forty-five test executions in total, every one of
them green on a clean `mvn verify`. That is a modest number for a production
codebase and exactly the right number for a reference monolith: enough
coverage per context to make the smells demonstrable, not so much that the
example stops being readable in one sitting.

{% include excalidraw.html file="test-pyramid" alt="The monolith's three-tier test pyramid: Tier 1 unit tests (4 classes, Mockito, every collaborator mocked) prove a class's logic does the right thing; Tier 2 slice tests (6 classes, @WebMvcTest, service mocked) prove a controller binds, validates, and serializes correctly; Tier 3 integration tests (3 classes, real Postgres via Testcontainers) prove the code works against a real database. 13 classes, 41 @Test methods plus 1 parameterized test expanding to 4 executions, 45 executions total, all green on mvn verify." caption="Figure 10.1 — The monolith's test pyramid: three tiers, thirteen classes, forty-five executions" %}

The three tiers answer three different questions, and keeping them separate
matters before looking at any one of them in detail:

- **Tier 1 (unit)** asks "does this one class's logic do the right thing,"
  with every collaborator mocked out. No Spring context, no database, no
  network — these tests run in milliseconds and they are where branching logic
  gets its most exhaustive coverage.
- **Tier 2 (slice)** asks "does this one controller bind, validate, and
  serialize correctly," with the service layer mocked and only the web stack
  — `@WebMvcTest` — booted. These tests catch the mistakes unit tests
  structurally cannot: a wrong `@RequestMapping`, a validation annotation that
  doesn't fire, a DTO field that doesn't serialize the way the contract
  promises.
- **Tier 3 (integration)** asks "does this actually work against a real
  Postgres," with everything wired up — real Hibernate mappings, a real
  Flyway-migrated schema, real transactions. These are slow and few by design;
  they exist to catch exactly the class of bug that mocking Tier 1's
  repositories would hide.

Maven keeps the three tiers in two separate build phases without a single line
of custom configuration, purely through a naming convention. Surefire — bound
to the default `test` phase — keeps its out-of-the-box include pattern,
`**/*Test.java`, which happens to match every Tier 1 and Tier 2 class and
none of the `*IT.java` files. Failsafe is bound explicitly to
`integration-test` and `verify`:

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

`mvn test` therefore runs only the fast tiers; `mvn verify` runs all three,
and fails the build on a red integration test exactly the way it already
failed on a red unit test. The fragile bit: the
split is driven entirely by a filename suffix, not by an annotation or a
plugin configuration that inspects what a test actually does. `SixContextsSmokeTest`
is the sharpest illustration of that fragility inside this very codebase — it
boots a full Spring context against a throwaway Testcontainers Postgres,
which is unambiguously integration-tier work, but because its filename ends
in `Test` rather than `IT`, Surefire's default pattern picks it up and it runs
in the fast `test` phase, not the `integration-test` phase, alongside
`OrderRepositoryIT` and `InventoryRepositoryIT`. It still runs, and `mvn
verify` still fails if it goes red, so nothing is broken — but a reader adding
a tenth integration-style test and naming it `*Test` out of habit would get
exactly this outcome without Maven ever complaining. The convention works
because it is consistent, not because it is enforced.

## Tier 1 — Mockito service unit tests

`OrderService` gets the deepest unit coverage of any class in the monolith,
and that is a deliberate choice, not an accident of which file a test author
happened to open first. It is both the god service flagged as `SMELL[ch.26]`
and the single in-process transaction flagged as `SMELL[ch.22]` — the one
class that touches five of the monolith's six bounded contexts in a single
method call. A class with that much responsibility concentrated in one place
is exactly where a regression is most expensive and least visible, so
`OrderServiceTest` exercises every branch `placeOrder` can take: the happy
path, out-of-stock, payment-declined, and customer-not-found, plus a
not-found case on the read side. Every collaborator — `OrderRepository`,
`CustomerRepository`, `InventoryService`, `PaymentService`, `ShippingService`,
`NotificationService` — is a Mockito mock; this test never opens a database
connection.

The payment-declined test is the one worth reading closely, because it proves
something about *ordering*, not just about a thrown exception:

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
    // unit test proves the orchestration order, not the rollback itself — the
    // rollback is exercised by the Testcontainers integration tier.
    verify(inventoryService).reserve("SKU-WIDGET-001", 1);
    verify(shippingService, never()).dispatch(any(), anyString());
    verify(notificationService, never()).sendOrderConfirmation(any(), any());
}
```

The `doThrow(...).when(paymentService).charge(...)` setup makes `charge` blow
up exactly where it would in the real `PaymentService`, and the three
`verify` calls at the end are the actual assertion: inventory *was* told to
reserve stock (the method got that far), but shipping and notification were
*never* called, because the exception unwound the call stack before either
ran. That is the orchestration contract a unit test can check cheaply —
*what happened before the exception, and what definitely didn't happen after
it* — and the comment left in the test is explicit about what it is not
proving: whether the in-memory reservation actually gets rolled back in the
database is a question only a real transaction can answer, which is exactly
why `OrderRepositoryIT` exists at Tier 3. That division of labor — Tier 1
proves sequence and collaboration, Tier 3 proves durability — recurs
throughout the suite. `InventoryServiceTest` follows the identical shape for
`reserve`'s own branching (sufficient stock decrements in place; insufficient
stock throws and leaves the quantity untouched; an unknown SKU throws a
different exception), and `PaymentServiceTest` uses a single
`@ParameterizedTest` with four string variants — `CARD-DECLINE`,
`card-decline`, `DECLINE-ANYTHING`, `visa-Decline-test` — to prove the demo
decline rule (any payment method containing "decline", case-insensitively) in
one test method instead of four near-identical copies.

## Tier 2 — `@WebMvcTest` controller slices

A unit test proves `OrderService` does the right thing when called correctly.
It says nothing about whether the HTTP layer calls it correctly — whether a
malformed JSON body gets rejected before it ever reaches the service, whether
a `201` carries the `Location` header a client is entitled to expect, whether
a thrown domain exception actually turns into the right status code. That is
`@WebMvcTest`'s job, and `OrderControllerTest` is the clearest example of it:

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

`@WebMvcTest(OrderController.class)` boots only the web MVC machinery around
that one controller — not the full `ApplicationContext`, not a database, not
the other five controllers — and `@MockitoBean` swaps in a mock `OrderService`
so the test controls exactly what the controller sees back from its one
collaborator. `@AutoConfigureMockMvc(addFilters = false)` disables Spring
Security's filter chain for this slice, a choice the class's javadoc is
explicit about: order endpoints carry no authentication rule of their own, so
disabling filters here tests the controller in isolation from a security
concern that belongs to Review, not Order — and Review's own write-path
authentication *was* tested the same way, with filters left *on*, in its own
controller test, before that test was deleted along with the rest of
Review's code when it left the monolith in r02/S10. That contract didn't
disappear with the test class: it now lives in the behavior-equivalence
suite's "Review Context Contract" folder (unauthenticated `POST` returns
`401`, authenticated `POST` returns `201`), run against
`examples/02-review-service`. The
other two tests in the class — an empty-items-list request returning `400`
with a `VALIDATION_FAILED` body, and an unknown order id returning `404` with
a `NOT_FOUND` body — exercise the error-mapping path the same way: the
`ResourceNotFoundException` the service throws has to come back out as the
right status and the right documented error shape, and that mapping lives
entirely in the web layer, invisible to a Tier 1 test that never serializes
anything.

## Tier 3 — `@DataJpaTest` and a real Postgres

The first two tiers prove the code is internally consistent. Neither one can
prove that `Order`'s JPA mappings actually resolve against Postgres, or that
a cascade persists child rows the way the entity's annotations claim it will
— proving that requires a real database, and `OrderRepositoryIT` is where that
happens:

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

`@AutoConfigureTestDatabase(replace = Replace.NONE)` is the one line that
makes this a Tier 3 test rather than a disguised Tier 1 test: Spring Boot's
`@DataJpaTest` defaults to swapping in an in-memory database, and `Replace.NONE`
turns that default off so the real `spring.datasource.*` properties — pointed
at the Testcontainers Postgres by `@DynamicPropertySource` — actually get
used. Without that one annotation, the test would silently run against H2
instead, and every `SMELL[ch.18]` comment in it would be proving nothing
about Postgres at all. The two assertions marked `SMELL[ch.18]` are the real
payload: they prove the direct foreign-key joins from `orders` into the
shared `customers` and `inventory_items` tables actually resolve through
Hibernate's `@ManyToOne` mappings against the Flyway-migrated schema — the
exact coupling Chapter 9 named as a smell, now pinned down by a test that will
have to be rewritten the day that coupling gets cured.
`InventoryRepositoryIT` does the equivalent work for the `PESSIMISTIC_WRITE`
lock query `InventoryService#reserve` depends on — a query Spring Data's
method-name parser will happily compile against a mock but can only be proven
correct against a real lock manager.

Both repository tests run a brand-new `PostgreSQLContainer` per test class
rather than sharing one through a common base class, and the javadoc on
`InventoryRepositoryIT` explains why: a static container field shared across
two top-level test classes gets torn down by the first class's `afterAll`,
breaking the second class's connection. Owning the container per class costs
a few extra seconds of container start-up per class; sharing it costs a
flaky, order-dependent build. `SixContextsSmokeTest` takes the same
self-contained pattern one level up, booting the entire Spring context with
`@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)` against its own
throwaway Postgres and hitting one REST endpoint per surviving bounded
context — order, inventory, payment, shipping, notification — plus a sixth
check that is really a decommission proof: a request to `/api/reviews` now
returns `404`, confirming that Review's controller, service, repository, and
entity were cleanly removed from the monolith once the strangler proxy's
cutover became permanent, with nothing else in the five remaining contexts
depending on the package that used to be there.

## How Testcontainers finds Postgres without a running stack

None of the three Tier 3 classes above configure a container runtime
anywhere in their source — there is no `docker.host` property file checked
into the repository, no environment-specific branch in the test code. That is
Testcontainers working as designed: at startup, its container-runtime client
looks for a reachable Docker Engine API along a short, ordered list of
places — the `DOCKER_HOST` environment variable first, then a
`~/.testcontainers.properties` override, then a handful of well-known local
socket paths — and connects to whichever one answers. Podman's rootless API
socket speaks that same Docker-compatible API, so once the local toolchain
exports `DOCKER_HOST` at the user's Podman socket (enabled once per machine
with `systemctl --user enable --now podman.socket`, the kind of one-time
setup this project's Chapter 1, "Prerequisites & the Toolchain," covers),
every `new PostgreSQLContainer<>("postgres:16-alpine")` call above just
works — no code in the monolith's test tree has any idea it is talking to
Podman rather than Docker, and none of it should have to. The one caveat
worth knowing before a test run mysteriously hangs: Testcontainers' Ryuk
resource-reaper, which normally guarantees a leaked container gets cleaned up
even after a crashed JVM, needs privileges that a locked-down rootless Podman
setup doesn't always grant by default; where that's the case, disabling Ryuk
(`TESTCONTAINERS_RYUK_DISABLED=true`) trades automatic cleanup for a test run
that actually starts, at the cost of remembering to `podman rm -f` an
abandoned container by hand once in a while. Either way, the self-provisioning
promise holds: nobody has to run `podman compose up` before `mvn verify` — the
Postgres instance these tests need is thrown away and rebuilt on every run,
which is exactly what makes them trustworthy rather than merely convenient.

## From a pyramid to a contract: why the equivalence suite is different

Everything above this line is **white-box**: it knows the monolith's
internals, calls its Java classes directly or through Spring's test slices,
and in Tier 3's case inspects a real database it is free to reset between
runs. That is the right shape for proving the monolith's *implementation* is
correct today. It is the wrong shape for the job Part 5 is about to need,
which is proving that a *completely different implementation* — a Quarkus
service, with its own schema, its own persistence layer, maybe its own
language idioms — still behaves the way the monolith did. A white-box test
can't even compile against code that doesn't share the monolith's class
names, let alone its database rows.

The technique this problem calls for has a name outside this book:
**characterization testing**, sometimes called golden-master testing — write
tests that pin down what a system *actually does*, observed from the outside,
before you change anything about how it does it. The discipline is strict
about sequencing: the suite gets written and run green **against the
unmodified monolith first**, capturing its real, current, possibly-smelly
behavior as the standard to hold every later version to — not the behavior a
specification says it *should* have, and not the behavior a cleaner rewrite
would produce if you got to design it from scratch. That is a deliberate,
almost uncomfortable choice: if the monolith's payment-decline path happens
to roll back a reservation inside the exact same ACID transaction as the
order write, the characterization suite captures *that*, warts and smells
included, rather than some more idealized future behavior. The point of
writing it before cutting anything is that it is the only artifact that can
answer the question "did this extraction change behavior," because
it is the only thing in the project that recorded what the behavior actually
was before the extraction happened. Write it after the fact — or worse,
write it against what the new service does and call that the baseline — and
there is nothing left to compare against; a regression and a
planned improvement look identical, because nothing captured the
"before" to tell them apart.

In this project that suite lives in `tooling/newman/mea.postman_collection.json`,
run with the `newman` CLI (Postman's collection runner), and it earns the name
**behavior-equivalence suite** for a specific, critical reason: every
request in it targets a `baseUrl` collection variable rather than a hardcoded
host, so the exact same collection can point at the monolith on one run and
at a freshly extracted Quarkus service on the next, with not one assertion
edited in between. Its companion concept is the **equivalence gate** — the
CI and per-chapter check that says an extraction is "done" only when this
unchanged collection passes against it. Four scenario folders carry that
contract today: a smoke check that the target is reachable and serving seeded
inventory; a happy-path checkout that confirms `201 Created`, the right total
and line items, a `Location` header, and — critically — that stock actually
drops by the ordered quantity afterward; an out-of-stock checkout that
confirms `409 Conflict` with a documented `OUT_OF_STOCK` error body and
*unchanged* stock; and a payment-declined checkout that confirms `402
Payment Required` and, again, unchanged stock — proving the same rollback
behavior `OrderServiceTest`'s comment flagged as untestable at the unit tier.
A fifth folder, "Review Context Contract," is a forward reference: it already
specifies the exact shape of Review's REST surface that Chapter 15's
extraction will have to reproduce, written against the monolith's existing
`/api/reviews` endpoints before that code moves anywhere.

{% include excalidraw.html file="whitebox-pyramid-vs-blackbox-equivalence" alt="Left, white-box: the Tier 1 through 3 pyramid calling monolith internals directly — OrderService, PaymentService, live Postgres rows — and unable to compile against a different implementation's class names. Right, black-box: the Newman behavior-equivalence suite, which sees only HTTP and targets a baseUrl collection variable, so the same unedited collection can point at the monolith today or a freshly extracted Quarkus service tomorrow." caption="Figure 10.2 — White-box pyramid versus black-box equivalence suite: same system, two vantage points" %}

Putting the same behavior next to its two different test-tier expressions
makes the contrast concrete. Here is the payment-decline branch proven at
Tier 1, by calling Java directly with every collaborator mocked, next to the
same behavior proven by the behavior-equivalence suite, by sending a real
HTTP request and reading only the response back:

{% include codetabs.html langs="Mockito (Tier 1 unit)|Newman (behavior-equivalence suite)" %}

```java
// OrderServiceTest — white-box: calls Java directly, every collaborator mocked.
doThrow(new PaymentDeclinedException("Payment method 'CARD-DECLINE' was declined"))
        .when(paymentService).charge(any(Order.class), anyLong(), eq("CARD-DECLINE"));

assertThatThrownBy(() -> orderService.placeOrder(command))
        .isInstanceOf(PaymentDeclinedException.class)
        .hasMessageContaining("declined");

verify(inventoryService).reserve("SKU-WIDGET-001", 1); // reserved...
verify(shippingService, never()).dispatch(any(), anyString()); // ...then stopped
```

```javascript
// mea.postman_collection.json — black-box: a real POST, only the HTTP
// response and a captured collection variable are visible.
pm.test('Declined payment returns 402 Payment Required', function () {
    pm.response.to.have.status(402);
});
var json = pm.response.json();
pm.test('Documented error contract: error=PAYMENT_DECLINED', function () {
    pm.expect(json.error).to.eql('PAYMENT_DECLINED');
});
// ...a later request re-reads /api/inventory/{sku} and compares it to
// widgetStockBeforeDecline, a value this same collection captured earlier —
// proving the reservation really rolled back, without ever touching the DB.
```

The Mockito test can see — and verify — that `shippingService.dispatch` was
never called; it is instrumenting the Java call graph directly. The Newman
assertion can see none of that; it only sees a status code, a JSON body, and
a second HTTP response it chose to fetch itself. That restriction is the
whole point. A suite that only ever asks "what does the outside world see"
is one that a Quarkus rewrite with a completely different internal call graph
can still satisfy, because nothing in the suite depends on there being a
`shippingService` to call in the first place. Capturing the "before" value —
`widgetStockBeforeDecline` — as a collection variable rather than a hardcoded
number is what lets the same collection be re-run safely against a long-lived
service with real, mutating data, instead of needing a fresh database reset
before every run; that property is exactly what the equivalence gate needs
once it runs unattended in CI rather than once by hand.

## The equivalence gate: what "done" means from here forward

This suite already has a track record in this project's own history.
Chapter 7 walked the full agentic development lifecycle run against
Review's extraction, and the behavior-equivalence suite built in this chapter
is the exact artifact it was gating against: sixteen assertions caught a
native-image serialization defect that every JVM-mode test run had missed,
and forty-nine assertions — run through the strangler proxy with the
monolith stopped outright — were the differential check that finally proved a
routing predicate was silently sending every request to the wrong backend.
Neither of those findings came from a new test suite written for the
occasion; both came from running *this* collection, unchanged, against a
moving target. That is the pattern every later extraction repeats: Chapter 15
cuts Review out first and is graded by the "Review Context Contract" folder
already sitting in the collection today; Notification, Inventory, Payment,
Shipping, and finally Order with its own gateway follow in Part 6 and beyond,
each one required to pass the same collection — extended with its own
scenario folder when its turn comes, never rewritten for the services that
came before it. The rule from `build-plan.md` Section G is blunt:
an extracted service is "done" only when it passes the *same* collection the
monolith passed, both as a per-chapter acceptance gate and as a check wired
into CI. A green suite is necessary evidence for every one of those cutovers.
Chapter 7 already showed, with real commits and a real routing bug, that it
is not sufficient evidence by itself — but that it is the thing that makes
the search for what else might be wrong possible in the first place, because
without it there would be nothing re-runnable to doubt.

## Build, run, observe

```bash
cd examples/00-monolith && mvn verify
```

A clean run shows Surefire's output first — the Tier 1 and Tier 2 classes,
including the smoke test despite its integration-grade setup — then Failsafe
picks up `OrderRepositoryIT` and `InventoryRepositoryIT` in the
`integration-test` phase, and `verify` fails the build if either tier is red.
To see the behavior-equivalence suite run against a live monolith:

```bash
# 1. Postgres for the running app (separate from the throwaway Testcontainers instances above)
podman compose --env-file .env up -d postgres

# 2. build and run the monolith; Flyway migrates and seeds automatically
cd examples/00-monolith && mvn -q -DskipTests package
SPRING_DATASOURCE_PASSWORD="$(grep ^POSTGRES_PASSWORD ../../.env | cut -d= -f2)" \
    java -jar target/monolith.jar

# 3. in another shell, from the project root
demos/demo-equivalence.sh
```

A green run reports every scenario folder passing against `http://localhost:8080`
— the monolith baseline this entire suite is captured from, and the baseline
every later extraction gets measured against.

## Cross-check

The suite's collection-variable design is itself testable without touching a
single assertion: run `demos/demo-equivalence.sh` twice in a row against the
same running monolith. If the suite had hardcoded stock levels instead of
capturing a `widgetStockBeforeHappyPath`/`widgetStockBeforeDecline` baseline on each pass, the
second run would fail — stock would already be lower than the number baked
into the collection from the first run's checkout. Seeing both runs pass
identically is a cheap, independent confirmation that the suite is safe to
run repeatedly against a long-lived target, which is exactly the property the
equivalence gate needs once it is running unattended in CI rather than once
by hand during authoring.

## What you learned

- The monolith's test pyramid separates three concerns: Tier 1
  Mockito unit tests prove branching logic and call ordering with nothing
  real behind them; Tier 2 `@WebMvcTest` slices prove the HTTP binding,
  validation, and error-mapping a unit test structurally can't see; Tier 3
  `@DataJpaTest` and `@SpringBootTest` classes prove the same logic survives
  contact with a real, Flyway-migrated Postgres, provisioned fresh by
  Testcontainers on every run over the local Podman socket.
- Surefire and Failsafe split those tiers by filename convention alone
  (`*Test` vs `*IT`) — powerful because it needs zero configuration, fragile
  because nothing stops a slow, container-backed test from being named
  `*Test` and quietly running in the fast inner loop, as `SixContextsSmokeTest`
  itself does.
- A white-box pyramid can prove a monolith's *implementation* is correct, but
  it cannot survive being pointed at a different implementation. A
  characterization/golden-master suite captures what the system does
  observably, from outside, before anything is cut — which is what makes the
  **behavior-equivalence suite** portable across a Spring monolith and a
  Quarkus extraction with nothing in common internally.
- The **equivalence gate** — this exact collection, re-run unchanged — is
  already proven essential: it is the artifact Chapter 7 showed catching a
  real native-image bug and anchoring the differential test that caught a
  real routing bug. Every extraction from Chapter 15 forward inherits that
  same gate rather than getting a new one invented for it.

The monolith now has code, deliberate smells, and a suite that proves both.
Part 4, starting with **Chapter 11, "Strategic & Tactical DDD and
Hexagonal,"** turns to the harder question this chapter's tests can verify
but can't answer on their own: given everything the smells and the
behavior-equivalence suite now make visible, where exactly does the first cut
go?

---

*Verification status: the test and tooling code this chapter walks is real
and already committed — `examples/00-monolith/src/test/` and
`tooling/newman/` — and its green runs are recorded in this project's own
commit ledger rather than re-executed for this chapter specifically: commit
`e6d62bc` ("add three-tier JUnit test layer to monolith... `mvn verify`
green") and commit `6cb113e` ("add behavior-equivalence suite (Newman);
49 assertions green on monolith"), both cited in Chapter 7. The test-method
and execution counts in this chapter (thirteen classes, forty-one `@Test`
methods, forty-five executions once the one parameterized test expands)
were counted directly against the files on disk during authoring, not copied
from an earlier summary — worth a quick `mvn verify` re-run on a real machine
before this count is treated as permanently fixed, since a future iteration
could add or remove a test without this prose noticing.*
