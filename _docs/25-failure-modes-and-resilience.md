---
title: "Failure Modes & the Resilience Chassis"
order: 25
part: "Coordinating Across Services"
description: "Partial-failure taxonomy; timeouts, retry + jitter, circuit breaker, bulkhead, load shedding; SmallRye Fault Tolerance as the Quarkus chassis."
---

Chapter 22 named the guarantee decomposition takes away — one process, one
write-ahead log, one atomic commit spanning four bounded contexts — and
Chapter 19 already showed you the first piece of machinery built to live
without it: a gRPC call that fails on a clock instead of hanging forever, and
a compensating `Release` that undoes a reservation no database transaction
spans anymore. This chapter does not introduce a new extraction or a new
runnable example. It does something this book has not done since Chapter 22:
stop, name the category of problem four chapters of extractions have each
been solving a slice of, and ask what the slice-by-slice fixes add up to once
you look at them as a single discipline rather than four separate ad hoc
decisions. Every mechanism below is either already running in this
repository — `examples/00-monolith/`'s `RemoteInventoryClient` and its gRPC
deadline, `OrderService#placeOrder`'s compensating `Release` loop, the
outbox's at-least-once publish paired with two independent layers of
consumer idempotency, `examples/01-strangler-proxy/`'s
`throwExceptionOnFailure=false` routing, and the `quarkus-smallrye-health`
dependency sitting in `examples/03-notification-service/pom.xml` and
`examples/04-inventory-service/pom.xml` — or it is a pattern this project has
not built yet, named here with the reason it was deferred. Part 7's two remaining chapters — Chapter 23's choreographed
saga for Payment and Chapter 24's orchestrated saga for Shipping, using the
Camel Saga EIP — are where the compensation idea this chapter organizes gets
built out to its full shape, coordinating multiple steps across multiple
services instead of the single reservation this chapter's evidence is drawn
from. This chapter is the frame they both sit inside.

## The taxonomy Kleppmann names: partial failure is the new normal

Martin Kleppmann's *Designing Data-Intensive Applications* devotes an entire
chapter — "The Trouble with Distributed Systems" — to a single observation
this book's monolith never had to confront and every extraction since
Chapter 15 has had to confront immediately: a network can lose a request, lose
a response, or simply take an unbounded amount of time to deliver either one,
and the caller on the other end of that network has no way to tell, from
inside its own process, which of those three things happened. A method call
inside one JVM either returns or throws — there is no third outcome. A gRPC
call across a network can return, throw, or simply never come back at all,
and that third outcome is not a corner case a careful implementation avoids;
it is a structural property of networks that a sufficiently patient
adversary (or an ordinary, unremarkable production incident) will eventually
produce. Kleppmann's sharper point, the one this chapter leans on hardest, is
that a node experiencing a problem cannot reliably distinguish its own
slowness from a slow peer, a slow network, or a peer that has already
crashed — "unreliable networks" and "unreliable clocks" are two faces of the
same underlying fact, that a distributed system has no shared, instantaneous
notion of "now" or "still alive" to fall back on. This is also, not
coincidentally, the backbone argument Chapter 22 already made from the data
side: ACD exists because CAP and PACELC force a choice between availability
and consistency the moment a network sits between two databases. This chapter
is the same argument made from the *caller's* side — what does the code that
issues a request across that same unreliable network have to do, concretely,
so that the unreliable network's failure does not become the whole system's
failure?

The monolith's checkout, before any extraction touched it, never had to ask
this question at all. `inventoryService.reserve(sku, qty)` was an ordinary
Java method call on the same call stack, and the only two outcomes it could
produce were "returned" and "threw," both of which happen in microseconds
against the same JVM's heap. The moment Chapter 19 moved that call behind a
gRPC stub reaching a separate process over a separate network, a third
outcome became possible for the first time in this project's history: the
call simply does not come back, because the inventory service is slow,
overloaded, mid-restart, or network-partitioned from the monolith, and
nothing in a plain, undecorated gRPC call tells the caller which of those is
true or how long to wait before giving up. Sam Newman's treatment of
resilience in *Building Microservices* names the consequence of ignoring
this directly: a synchronous call with no bound on how long it can take is a
**latent outage waiting on a schedule you don't control** — every checkout
thread that calls `reserve` and gets nothing back stays blocked, holding
whatever connection-pool slot or request-handling thread it occupied, for as
long as the far side takes to answer (which, with no boundary in place, might
be forever). A handful of slow calls degrades latency. Enough of them,
blocking long enough, exhausts the monolith's own thread pool and turns one
slow downstream dependency into a total outage of a service that has nothing
wrong with it. That failure mode — a healthy caller brought down entirely by
an unhealthy dependency it never stopped waiting on — is specifically what
Michael Nygard's *Release It!* catalogued as cascading failure, and it is the
reason the very first resilience mechanism this project built was not
exotic: it was a clock.

## Timeouts and deadlines: the gRPC client that fails cleanly instead of hanging

`RemoteInventoryClient#reserve`, already quoted in Chapter 19, is worth
reading again here for what it is from this chapter's angle: the simplest
possible answer to the latent-outage problem just described, already
running, already tested, with nothing hypothetical about it.

```java
// examples/00-monolith/.../inventory/RemoteInventoryClient.java
public ReserveResult reserve(String sku, int quantity) {
    ReserveReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            .reserve(ReserveRequest.newBuilder()
                    .setStockKeepingUnit(sku)
                    .setRequestedQty(quantity)
                    .build());
    return new ReserveResult(reply.getReservationOk(), reply.getOnHandQty());
}
```

The extracted order service — the current gRPC caller of the inventory
service now that the monolith is decommissioned — carries this same
`withDeadlineAfter(inventoryGrpcTimeoutMs, TimeUnit.MILLISECONDS)` deadline
forward in its own `RemoteInventoryClient`, so the bound this excerpt shows
is live in the finished system, not only in the monolith frozen here.

`withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)` — bound to
`inventory.grpc.timeout-ms`, defaulting to five thousand — converts "wait
indefinitely" into "wait at most this long, then fail loudly." That
distinction is the entire value of a deadline, and it is worth being precise
about why *that* value and not a larger or smaller one: five seconds is long
enough to absorb an ordinary GC pause or a brief connection-pool contention
spike on the inventory service's side without a false-positive timeout on
perfectly healthy traffic, and short enough that a hung or
unreachable inventory service fails a checkout attempt in a bounded,
customer-tolerable window rather than leaving a browser tab spinning. Nothing
about five thousand milliseconds is a law of nature — it is a tuned constant,
exactly the kind of fragile, environment-specific number Chapter 20 already
asked you to expect and re-check for the outbox's own poll interval, and a
reader deploying this client against a slower network or a heavier-loaded
inventory service should expect to retune it rather than treat it as handed
down from the framework.

What happens when the deadline fires is the second half of the design, and
it is just as deliberate as the timeout value itself. The blocking stub
throws an unchecked `StatusRuntimeException` — `DEADLINE_EXCEEDED` if the
clock ran out, `UNAVAILABLE` if the channel could never connect at all — and
`reserve` does not catch it. The class's own javadoc states the reasoning: a deadline exceeded is not a logical "insufficient stock" result, so
it must never be mapped to `InsufficientStockException`'s `409`, and Spring's
default handling of an unmapped `RuntimeException` turns it into a `500`.
That `500` is not a bug the project failed to polish away — it is the
accurate answer. A checkout that cannot determine whether a reservation
succeeded must not report success, and it must not silently retry into a
state nobody can audit; failing loudly, with a status code that tells an
operator "something is actually broken here," is strictly better than a
checkout that confirms an order against a reservation whose outcome nobody
actually knows. This is the same instinct Chapter 16's anti-corruption layer
and Chapter 19's typed proto already modeled in a different shape: don't let
an ambiguous outcome masquerade as a known one. A timeout is not graceful
degradation and should not be dressed up as one; it is a fast
failure that trades "maybe eventually correct" for "definitely and promptly
explicit about not knowing."

A deadline alone does not buy everything: it bounds how
long one call can block; it says nothing about what the caller should do
next — retry, give up, or ask a different question entirely — and it does
nothing to protect the caller's own resources from a dependency that is
*reliably* slow rather than occasionally hung, which is exactly the problem a
circuit breaker is built to solve and this project has not yet built one for.

{% include excalidraw.html file="timeout-three-outcomes" alt="A checkout thread's call to RemoteInventoryClient#reserve branches into three outcomes once it crosses the gRPC boundary: returns a ReserveReply, throws a StatusRuntimeException mapped to a 500, or never returns at all. The never-returns branch leads to a blocked thread holding its pool slot and, without a bound in place, cascading failure as enough blocked threads exhaust the pool. A ghost box shows the deadline that bounds this outcome in this project's own code today." caption="Figure 25.1 — A gRPC call's third outcome, and the cascading-failure path a deadline cuts off" %}

## Retries demand idempotency: the outbox's at-least-once contract, reread

Chapter 20 already walked the transactional outbox at the depth this book's
standard requires, and this chapter is not repeating that walkthrough — it is
pulling one thread out of it and naming it as a general resilience principle
rather than an outbox-specific detail. The principle is this: **a retry is
only safe if the thing being retried is idempotent**, and every retry this
project has built, without exception, exists paired with an idempotency
mechanism on the receiving end rather than a bare "try again and hope."

`OutboxRelay`'s own retry is the simplest version, and it is a retry by
omission rather than an explicit retry loop: `publishOne`'s `catch` block
does nothing but let the row stay unpublished, so the next scheduled tick —
two seconds later, by default — picks the same row up and sends the
identical payload again.

```java
// examples/00-monolith/.../common/outbox/OutboxRelay.java (excerpt)
private void publishOne(OutboxEvent event) {
    try {
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                .get(5, TimeUnit.SECONDS);
        event.markPublished();
        outboxRepository.save(event);
    } catch (ExecutionException | TimeoutException e) {
        // left unpublished; picked up again next tick
    }
}
```

That is a retry with no backoff, no jitter, and no cap — the same five-second
timeout and the same two-second poll interval apply to the hundredth attempt
at a permanently failing row as to the first. Chapter 20 already named the
cost of that gap directly: a message the broker will never accept (an
oversized payload, say) gets retried forever, consuming one of the relay's
fifty per-tick slots on every single poll, indefinitely. This chapter is not
re-litigating that gap; it is pointing at it as the concrete instance of a
more general resilience concern — **unbounded retry** — that the next
section names explicitly as deferred, alongside circuit breakers and
bulkheads, for the same scope-discipline reason.

What makes the relay's retry *safe to attempt at all*, despite that gap, is
that the broker side of the retry can duplicate a delivery without
corrupting anything, because the consumer was built from the start to expect
duplicates. `NotificationService#recordOrderPlaced` is idempotent on two
independent layers, not one, and this is the chapter where "why two layers,
not one" becomes a general resilience lesson rather than a
notification-specific implementation note:

```java
// examples/03-notification-service/.../NotificationService.java
@Transactional
public void recordOrderPlaced(OrderPlacedEvent event) {
    if (repository.findByOrderId(event.orderId()) != null) {
        return;
    }
    Notification notification =
            new Notification(event.customerId(), event.orderId(), "EMAIL", event.confirmationMessage());
    repository.persist(notification);
}
```

```sql
-- examples/03-notification-service/.../V3__idempotent_order_id.sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_order_id
    ON notification.notifications (order_id)
    WHERE order_id IS NOT NULL;
```

The application-level check-then-insert is the cheap, common-case path — an
ordinary redelivery costs one indexed read and a no-op return. The partial
unique index is the backstop for the one case application code structurally
cannot close on its own: two deliveries racing concurrently, both passing the
`findByOrderId` check before either commit lands. Chapter 17's own test,
`OrderPlacedConsumerTest#redeliveryOfSameOrderIsIdempotent`, sent the
identical event twice through the real `@Incoming` pipeline and held the
notification count at exactly one for a continuous three-second window —
proof, not assertion, that retry-by-redelivery and idempotency-by-constraint
compose correctly together. Chapter 19's CDC consumer applies the identical
principle in a different shape: its `ON CONFLICT`-keyed upsert, keyed by the
same primary key the source row carries, absorbs Kafka's at-least-once
redelivery without ever double-applying a stock update, proven by
`InventoryCdcConsumerTest` sending the same envelope twice and watching
quantity settle at the correct value rather than drift. Kleppmann's name for
the combination — at-least-once delivery paired with idempotent processing —
is *effectively-once*, and it is the only version of "exactly-once" that
survives contact with a real, unreliable network. A retry without that
pairing is not resilience; it is a second, independent way to corrupt state,
and every retry in this codebase was built with its idempotency half in
place before its retry half was trusted.

## Where a circuit breaker and a bulkhead would go — and why they aren't here yet

A deadline answers "how long do I wait on one call." It does not answer a
different, harder question a sufficiently unhealthy dependency eventually
forces: once a downstream service has failed its last several calls in a
row, should the caller keep trying it at the same rate, or stop trying
altogether for a while? Michael Nygard's **circuit breaker** pattern answers
that question by wrapping a call with a small state machine — closed
(calls flow through normally), open (calls fail immediately, without even
attempting the network round-trip, once a failure threshold trips), and
half-open (a trial call periodically checks whether the dependency has
recovered before the breaker closes again). The payoff a breaker buys over a
bare deadline is specifically about load: once a dependency is known to be
down, a five-second deadline on every single call still means every retrying
caller pays that full five seconds before giving up, over and over, adding
load to a struggling dependency and holding resources on the caller's side
for the full deadline window on every attempt. An open breaker short-circuits
that entirely — it fails in microseconds, not seconds, the moment it already
knows the answer, and it stops hammering a dependency that is trying to
recover.

{% include excalidraw.html file="circuit-breaker-states" alt="The circuit breaker state machine: closed, where calls flow through normally; open, where calls fail immediately without attempting the network round-trip once a failure threshold trips; and half-open, where a trial call checks whether the dependency has recovered before the breaker closes again or a failed trial re-opens it. A dashed callout marks RemoteInventoryClient#reserve and #release as where this would wrap, and notes that no @CircuitBreaker annotation exists in this codebase today." caption="Figure 25.2 — The circuit breaker's closed/open/half-open state machine, named here and not yet built" %}

In this system, the place a breaker would wrap is unambiguous:
`RemoteInventoryClient`'s `reserve` and `release` calls, the one synchronous,
mutating, hot-path collaborator this project has built so far. MicroProfile
Fault Tolerance — implemented in this project's stack by SmallRye Fault
Tolerance, available to every Quarkus service already in this repository —
is exactly the chassis this call would reach for: `@Timeout` would express
the deadline declaratively instead of the explicit `withDeadlineAfter` call
sites now carry by hand; `@CircuitBreaker` would add the open/half-open state
machine around it; `@Bulkhead` would cap how many concurrent calls to
inventory the monolith is willing to have in flight at once, so a slow
inventory service starves only the checkout paths actually calling it rather
than exhausting a thread pool shared with every other request the monolith
serves. None of those three annotations exist anywhere in this codebase
today — not in `examples/00-monolith/pom.xml`, not in
`examples/04-inventory-service/pom.xml`, not anywhere this project's build
reaches. That is a correct state of affairs for this book's scope, not an
oversight, for two reasons.

First, the irony is structural: the call that would most benefit from a
circuit breaker is issued from `RemoteInventoryClient`, which lives inside
the *Spring Boot monolith*, not inside a Quarkus service — SmallRye Fault
Tolerance is a Quarkus-side chassis capability, and the monolith's
equivalent would be a library like Resilience4j, which this project has
also not added. The resilience gap, for as long as inventory's call site
remains inside the monolith, sits on the Spring side of this book's
before/after contrast, not the Quarkus side — relevant when Chapter 27's
chassis chapter argues for what Quarkus provides, because a chassis
capability a framework offers is only a chassis capability a *service* has
if someone wires it in.

Second, and more fundamentally: this project's own Chapter 3 named the
standing discipline a circuit breaker would otherwise violate if added
speculatively — **"microservices are not the goal of building
microservices"** — and this book's scope-discipline posture has consistently
demoted a chassis capability to a named, deferred gap rather than building it
because a pattern catalog says a resilience chassis should have one. A
circuit breaker is worth the added complexity when a dependency's *failure rate*, not
merely its occasional slowness, is high enough that short-circuiting beats
retrying — a threshold this project's own traffic, a demo-scale walking
skeleton with one inventory service and no production load pattern behind
it, has never been measured against. Adding one now would be
exactly the speculative infrastructure Chapter 3's own standard argues
against: a resilience pattern this book can show the reader on a diagram, not
one this book can prove is worth its cost the way every other pattern in this
chapter has been proven — by breaking something and watching the
fix catch it. The right move is to name the gap, name where the fix
goes, name the chassis that would supply it, and leave it for the reader's
own production traffic to justify, rather than manufacture a load profile
this book doesn't have, purely to complete the pattern-catalog
checklist. Bulkheads follow the identical logic: this project's one gRPC
channel to inventory is not yet isolated from the monolith's other I/O by a
dedicated thread pool or connection-pool partition, because nothing in this
book's traffic has yet demonstrated that one slow dependency's resource
consumption starves an unrelated request path — the day it does,
the fix is a `@Bulkhead` annotation away, not a redesign.

## Compensation over rollback: the saga-lite already built

Chapter 22 drew the line precisely: the monolith's checkout used to get
atomic rollback across four bounded contexts as a side effect, paid for by one
`@Transactional` boundary and one database's write-ahead log, and that automatic
rollback cannot survive a decrement that commits in another service's own
database. What replaces it is not automatic. `OrderService#placeOrder`
tracks every sku this checkout has successfully reserved remotely, and its
surrounding `catch` block issues a compensating `Release` for each one on
*any* failure that happens afterward:

```java
// examples/00-monolith/.../order/OrderService.java (excerpt)
try {
    // ... reserve each line via RemoteInventoryClient, track remoteReservations ...
    paymentService.charge(order, order.getTotalCents(), command.paymentMethod());
    // ...
} catch (RuntimeException ex) {
    compensateRemoteReservations(remoteReservations); // DRQ-042
    throw ex;
}
```

This chapter's framing of that mechanism is the one this book wants you to
carry into Part 7's next two chapters: a compensating action is not a
rollback. A rollback is a property the *database* gives you, atomically, for
free, the instant you declare a transaction boundary. A compensating action
is a second, independent, forward-moving operation that a human had to write,
name, and reason about separately from the operation it undoes — `release`
is not `reserve` running backward inside the same mechanism; it is its own
RPC, with its own failure modes, that the original caller is responsible for
invoking at exactly the right moment. `compensateRemoteReservations`'s own
javadoc is explicit about how small this particular saga-lite stays:
best-effort, logged loudly on failure rather than swallowed silently,
with no idempotency key and no saga ledger, because a single reservation with
a single compensating call does not yet need the machinery a saga spanning
several services and several steps does. That is this chapter's
hinge into Part 7's remaining two chapters. Chapter 23 builds the same
underlying idea — an event triggers a step, a later step's failure triggers a
compensation for everything that already succeeded — across *multiple*
services coordinated by nothing but events flowing between them
(`order.placed` → `payment.captured`/`payment.declined`), the choreographed
control style. Chapter 24 builds the identical outcome for Shipping through
the opposite control style: a central orchestrator, using the Camel Saga EIP,
explicitly sequencing each step and explicitly invoking the matching
compensation the moment any step reports failure. Both inherit, and have to
outgrow, the exact limitation this chapter's `Release` javadoc already
names — no idempotency key yet on the compensating call itself, which means
a retried `Release` whose effect already landed would over-restore stock, a
gap this chapter is naming rather than quietly carrying forward unexamined.

## Dead letters and poison messages: the gap Chapter 20 named, generalized

Chapter 20 already gave this gap its full treatment in the outbox's own
terms, and this chapter's job is only to lift it out of that one mechanism
and name it as a general category. A **poison message** is a message a
consumer (or, as in this project's case, a relay publishing to a consumer)
can never successfully process no matter how many times it is retried — an
oversized payload, a schema a deployed version can no longer parse, a
business invariant the message itself violates. `OutboxRelay`'s `catch`
block treats every failure identically, transient or permanent, which means
a poisoned row competes for one of the relay's fifty per-tick slots
on every single poll, forever, never making progress and never getting out
of the way of healthy events behind it. The textbook fix — a **dead-letter
queue**, a separate topic or table a poisoned message gets moved to after a
bounded number of attempts, where a human or an automated remediation process
can inspect it without it clogging the primary path — is not built anywhere
in this project. Neither the outbox relay nor either Quarkus consumer
(`OrderPlacedConsumer`, `InventoryCdcConsumer`) tracks a per-message attempt
count or routes a message anywhere but back into the same retry loop on
failure. That is a real, named gap, not a quietly-fixed one, and it sits in
the same category as the circuit breaker: worth building the day this
project's own traffic produces an actual poisoned message that needs one,
not before.

## Graceful degradation, or the deliberate choice not to fake it

"Graceful degradation" usually means a system keeps serving a *reduced* but
still useful response when a dependency fails, rather than failing the whole
request. This project's strangler proxy makes a pointed, opposite choice at
exactly the seam where that temptation would be strongest.
`StranglerProxyRoute`'s own in-code comment states it directly:
`throwExceptionOnFailure=false` stops Camel from turning a backend's 4xx or
5xx response into a thrown Camel exception, so the real status code and body
flow straight back to the caller — not a proxy-manufactured, cosmetically
softer response standing in for it. That is a deliberate rejection of
graceful degradation at the routing layer, and the reason is specific to
this project's purpose: the behavior-equivalence suite needs to see the
*real* status code a backend produced, because a proxy that silently
converted a `500` into something friendlier would be lying to the one
instrument this book relies on to prove two backends behave the same way.
Transparency at the seam is a precondition for the equivalence gate meaning
anything at all.

That does not mean graceful degradation has no place in this system — it
means this book is precise about *which* failures tolerate it and which
don't, echoing the calibration Chapter 22 already drew for consistency.
Chapter 19 named the sharpest case directly: stock reservation cannot degrade
gracefully, because serving a customer a confirmed order against stock that
was never actually secured is not a reduced experience, it is a wrong one — a
correctness failure no compensating action can fully repair after the fact
(you can refund a card; you cannot always produce a second physical widget
that doesn't exist). A feature that tolerates staleness — a
product page's displayed stock count, read from the inventory service's
CDC-replicated copy a few hundred milliseconds behind the source of truth —
is exactly the kind of place graceful degradation belongs, and Chapter 19's
own bounded-wait reads already treat it that way. The discipline this
chapter asks you to take forward is the same one ACD asked for: decide,
invariant by invariant, whether a degraded answer is accurate or merely
convenient, and never apply "fail soft" to a case where failing hard is the
only answer a customer can actually trust.

## Health checks and readiness: what `quarkus-smallrye-health` buys, and who doesn't have it yet

Of every pattern this chapter has walked, health checks are the one piece of
the chassis already sitting in this project's dependency tree, on the
Quarkus side, waiting for Part 9's platform to actually consume it.
`examples/03-notification-service/pom.xml` and
`examples/04-inventory-service/pom.xml` both declare
`quarkus-smallrye-health`, which gives each service the standard MicroProfile
Health surface — `/q/health/live` (is the process itself running) and
`/q/health/ready` (is it ready to accept traffic) — without either service
writing a line of health-check code by hand. The payoff is larger than it
looks for notification-service specifically: SmallRye Reactive Messaging
ships its own health-check integration that wires straight into this same
surface, so the Kafka consumer's connection state becomes part of
`/q/health/ready` automatically, the moment the extension is on the
classpath — a dependency a reader never has to configure to get a readiness
probe that reflects whether the service can consume
`order.placed`, not merely whether its HTTP port is open.

What this project does *not* yet have is the other half of why health checks
matter: nothing in `examples/01-strangler-proxy/` or the compose/CI
definitions in this repository currently wires a platform's liveness/readiness
probes to these endpoints to gate traffic or restarts — that wiring is Part
9's subject (Chapters 29–30, on minikube), not this chapter's. The asymmetry:
`examples/00-monolith/pom.xml` carries no actuator or health dependency at
all, and `examples/01-strangler-proxy/` has no health check of its own
either. The resilience chassis this chapter catalogs is, today, a Quarkus-side
capability that exists on the two newest services and nowhere else in this
system — one more data point, named directly, for the "what you migrated to"
argument Chapter 27 makes in full.

## Proving it: the behavior-equivalence suite's negative checks

Every claim this chapter has made about failure handling would be exactly as
credible as a code comment if nothing in this project had ever
broken a dependency and watched the system's response. Chapter 7
already stated the general principle this chapter's resilience claims lean
on entirely: a green suite run is evidence, not proof, because a
behavior-equivalence suite verifies the *response* a request produced, not
the *path* it took to produce it — and when two backends can agree by
coincidence (Review's shared-table routing bug) or when a suite never
actually tries to break anything (a suite that only ever runs against a
healthy system), passing assertions prove nothing about resilience at all.
The **negative check** — deliberately killing the dependency a claim depends
on and confirming the suite goes RED, then restarting it and confirming the
suite recovers GREEN — is the only thing in this book's toolkit that
converts a resilience claim from asserted to demonstrated.

Chapter 17's version of this check killed the notification-service process
outright and re-ran the Notification Context Contract folder through the
proxy, producing a `500` and a JSON parse error — a *stronger* failure than a
soft budget exhaustion would have been, because it proved the suite's
bounded-wait poll was waiting on a real pipeline rather than a
lenient timeout that could be excused as slow infrastructure. Chapter 19's
version killed the inventory service entirely with both cutover flags set,
and the result was not a soft degradation but an immediate, precisely scoped
failure: thirty-four of fifty-six assertions failed, every one of them on the
inventory seam specifically, while Review and Notification's own folders
stayed green — proof the failure was scoped to the dependency that was
actually down, not a proxy-wide outage masquerading as one. Both negative
checks ended the identical way: the dependency restarted, the exact same
unedited collection re-run, and a return to green — RED-on-kill paired with
GREEN-on-restart, run with nothing edited in between, which is the only
combination that rules out both "the suite is simply broken" and "the suite
never meant anything."

This is the testing angle every pattern above should be read through. A
deadline that has never actually been hit by a real timeout is a number in a
config file. A compensating `Release` that has never actually run against a
forced payment decline is a method nobody has proven does what its javadoc
claims. `CUTOVER.md`'s own captured Scenario 3 evidence — stock reserved,
payment declined, `Release` fired, stock restored to its exact pre-checkout
value, verified by reading two independent databases and finding them
converge — is what turns "this project has compensation" from a claim into a
demonstrated fact. None of this is optional polish on top of the resilience
mechanisms above; it is the only reason any of the claims in this chapter are
trustworthy at all, and it is why `.github/workflows/code-ci.yml`'s
equivalence-gate jobs for both Notification and Inventory were each proven
red-then-green on a deliberate break before either was trusted as a
permanent CI gate.

## The resilience scorecard

Pulled together, here is exactly what this project has built against the
canonical resilience chassis, and exactly what it has left for
a reader's own production traffic to justify:

**Implemented, real, and proven by a negative check:** a per-call gRPC
deadline on the one synchronous, mutating hot-path collaborator this project
has (`RemoteInventoryClient`, `inventory.grpc.timeout-ms`); at-least-once
delivery paired with two-layer idempotent consumption on every asynchronous
path (the outbox relay plus `NotificationService`'s check-then-insert and
partial unique index; the CDC consumer's `ON CONFLICT` upsert); an explicit
compensating action in place of a database rollback the moment a write
crosses a service boundary (`compensateRemoteReservations`); transparent,
un-degraded failure propagation at the proxy layer
(`throwExceptionOnFailure=false`); and MicroProfile Health endpoints,
including automatic broker-connectivity health, on both Quarkus services.

**Deferred by design, with a stated
reason:** a circuit breaker around the inventory gRPC call (no measured
failure rate yet justifies short-circuiting over retrying, and the call site
lives in the Spring monolith, not yet on a Quarkus chassis that would make it
a one-annotation addition); a bulkhead isolating that same call's resource
consumption from the rest of the monolith's I/O (no observed starvation yet
to fix); retry backoff, jitter, and a retry cap on the outbox relay (Chapter
20's own named gap); a dead-letter path for a message that can never
successfully process (the same chapter's poison-message gap, generalized
here); platform-level consumption of the health endpoints that already exist
(Part 9's subject, not this chapter's); and an idempotency key on the
compensating `Release` call itself (named in its own javadoc and inherited
as a stated gap by both of Part 7's saga chapters, not patched over
here).

{% include excalidraw.html file="resilience-scorecard" alt="A two-column scorecard. Left, implemented and proven by a negative check: the per-call gRPC deadline, at-least-once delivery paired with two-layer idempotent consumption, the explicit compensating action in place of rollback, transparent un-degraded failure propagation at the proxy, and MicroProfile Health endpoints on both Quarkus services. Right, deferred by design with a stated reason: a circuit breaker, a bulkhead, retry backoff/jitter/cap on the outbox relay, a dead-letter path, platform-level consumption of the health endpoints, and an idempotency key on the compensating Release call." caption="Figure 25.3 — The resilience scorecard: what this project built, and what it left for measured traffic to justify" %}

Chapter 3 named the discipline this scorecard is an instance of:
**microservices are not the goal**, and neither is a resilience pattern
catalog completed for its own sake. Every implemented item above exists
because a specific extraction's specific failure mode demanded it and this
book's own negative checks proved the fix worked. Every deferred item above
is deferred because this project's actual traffic, at the scale this book
demonstrates, has not yet produced the failure that would justify the added
complexity — not because the pattern is unknown, and not because it would be
hard to add. The chassis a system needs is the chassis its own measured
failure modes demand, not the chassis a diagram says every microservice
should carry.

## What you learned

- A distributed system's defining property, in Kleppmann's framing, is that a
  caller cannot distinguish a slow peer from a dead one from a slow network —
  which is exactly the ambiguity a bare, undecorated network call inherits
  the instant an in-process method call becomes a gRPC request, and exactly
  the ambiguity `RemoteInventoryClient`'s deadline exists to bound rather
  than eliminate.
- A **retry is only as safe as the idempotency behind it** — this project's
  outbox relay retries by doing nothing but leaving a row unpublished, and
  that retry is safe only because every consumer on the far side absorbs
  duplicate delivery on two independent layers, proven by tests that send
  the same event twice and check the state settles once.
- **Compensation is not rollback.** A database gives you atomic rollback for
  free, inside its own boundary; once a write crosses a service boundary, a
  human has to write, name, and test an explicit compensating action, and
  this project's `Release` call is the smallest possible instance of the
  pattern Chapter 23 and Chapter 24 build out to full saga scale.
- **Circuit breakers, bulkheads, retry budgets, and dead-letter routing are
  real, named, and deferred by design** — not because they're unknown
  patterns, but because this project's own measured failure modes haven't
  yet demanded them, and building them speculatively would be exactly the
  infrastructure-before-need Chapter 3 already argued against.
- **A resilience claim is unverified until a negative check proves it** —
  killing a dependency and watching the behavior-equivalence suite go RED,
  then restarting it and watching the suite recover GREEN, is the only
  evidence in this book's toolkit that turns a timeout, a compensating call,
  or an idempotent consumer from an asserted property into a demonstrated
  one.

Chapter 23 picks up the compensation pattern this chapter organized and
builds it across multiple services for the first time, choreographed through
events alone — `order.placed` triggering Payment's attempt, `payment.captured`
or `payment.declined` triggering whatever reacts to it next, with no central
coordinator. Chapter 24 builds the identical guarantee for Shipping through
the opposite control style, an explicit orchestrator using the Camel Saga
EIP. Both inherit the deadline discipline, the idempotency discipline, and
the negative-check discipline this chapter named — and both have to decide,
for themselves, exactly where their own circuit breakers and dead-letter
paths would go, and exactly how long they can defer building them.

---

*Verification status: not applicable — this is a conceptual chapter with no
new runnable example under `examples/`, in the same standing as Chapter 22.
Every artifact it cites is real code and real, dated evidence already in
this repository: `examples/00-monolith/` (`RemoteInventoryClient#reserve`/
`#release` and their deadline/javadoc, `OrderService#placeOrder`/
`#compensateRemoteReservations`, `common/outbox/OutboxEvent`/`OutboxRelay`),
`examples/01-strangler-proxy/` (`StranglerProxyRoute.java`'s
`throwExceptionOnFailure=false` routing and `CUTOVER.md`'s negative-check
timelines for both Notification and Inventory), `examples/03-notification-service/`
(`NotificationService#recordOrderPlaced`, `V3__idempotent_order_id.sql`,
`OrderPlacedConsumerTest`, and `pom.xml`'s `quarkus-smallrye-health`
dependency), `examples/04-inventory-service/` (`InventoryCdcConsumer`,
`InventoryCdcConsumerTest`, and `pom.xml`'s `quarkus-smallrye-health`
dependency), and `_plans/decisions.md` (DRQ-034, DRQ-037, DRQ-041,
DRQ-042, DRQ-046). The captured outputs quoted above are taken verbatim from
`CUTOVER.md` and `MIGRATION.md` rather than re-run live here, per this
project's own DRQ-025 discipline. What a reader's own run should confirm
independently: that no fault-tolerance or circuit-breaker dependency has
been added to any service's build since this chapter was written (the
deferred-items list above is a snapshot, not a permanent ceiling); and that
the deadline value and poll interval cited above still match the committed
`application.properties`/`OutboxRelay` configuration, since both are tuned
constants this book has already asked you to re-verify once, in Chapters 19
and 20 respectively.*
