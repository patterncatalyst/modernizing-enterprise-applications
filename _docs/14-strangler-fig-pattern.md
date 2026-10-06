---
title: "The Strangler Fig Pattern"
order: 14
part: "The Strangler Fig in Practice"
description: "Fowler's pattern, the three steps, the proxy/redirection/shared-database variants, and the Camel strangler proxy used for cutover in this book."
---

Chapter 13 closed Part 4 with a ranked list, not a plan: six bounded
contexts, scored by afferent and efferent coupling, from review's
near-zero score to order's maximal one. A ranked list tells you which
seam to cut first. It does not tell you *how* to cut a seam in a running
production system without a weekend outage and a prayer. That is this
chapter's job, and it is the first chapter in Part 5 with running code
behind every claim it makes, because the mechanism this chapter
describes is not a diagram borrowed from a textbook — it is the actual
Camel route sitting in front of this book's monolith today, already
exercised against a real cutover, a real bug, and a real decommission.

The code is in `examples/01-strangler-proxy/`. There is no run script in
this directory beyond the Maven/Quarkus build itself — the proxy is a
long-running process you start and leave up, and its `README.md` covers
exactly how to build it, run it, and point the behavior-equivalence
suite through it instead of at the monolith directly.

{% include excalidraw.html file="strangler-review-extraction" alt="Two stacked diagrams showing the Camel strangler proxy before and after the Review cutover: before, the proxy forwards /api/reviews traffic to the Spring monolith; after, the flag strangler.review.enabled routes that traffic to the Quarkus review service instead, with the monolith's Review module decommissioned." caption="Figure 14.1 — The strangler proxy before and after the Review cutover: one Camel route, one flag, two backends." %}

## Fowler's pattern, and why the metaphor fits

Martin Fowler named this pattern after a real botanical process, and the
metaphor is unusually precise for an engineering analogy, which is why
it has survived two decades of pattern inflation while flashier names
have not. A strangler fig begins life as a seed dropped in the canopy of
a host tree. It sends roots down the host's trunk toward the ground and
grows a lattice of its own structure around the host's entire length,
all while the host keeps standing, keeps photosynthesizing, keeps doing
everything a tree does. Only once the fig's own structure is complete —
once it can stand on its own, draw its own water, bear its own
weight — does the host, no longer able to compete for light and
nutrients inside the fig's lattice, die and rot away, leaving the fig
standing in exactly the shape the host used to occupy.

Translate that botanical sequence into four engineering moves and you
have the whole pattern, in an order that matters:

1. **Intercept.** Before a single line of new-system code exists, put
   something in front of the old system that can see every request
   headed its way. This is the fig's seed taking root in the canopy —
   nothing about the host has changed yet, but the mechanism that will
   eventually redirect its traffic now exists.
2. **Route.** Decide, per request, which system answers it. At first
   the answer is always "the old system," because nothing new has been
   built yet — but the *decision point* now exists, separate from the
   old system's own code, which is the property that makes everything
   after this step possible without touching the old system again.
3. **Incrementally replace.** Build one new piece of functionality,
   stand it up as its own service, and flip the routing decision for
   *that piece only* so the new service — not the old code — now
   answers it. Repeat, one piece at a time, verifying each cutover
   independently before moving to the next.
4. **Retire.** Once a piece of the old system no longer receives any
   traffic, the fig's roots have done their work and the host's
   corresponding limb can be removed. This is the step every reader's
   instinct wants to treat as the real goal — a smaller, more modern
   old system — but it is also the step this chapter will spend the
   most time insisting you get in the right order, because retiring
   too early is how "incremental" migrations quietly turn into
   big-bang rewrites with extra ceremony.

{% include excalidraw.html file="strangler-four-moves" alt="The strangler fig pattern's four moves in order -- intercept, route, incrementally replace, retire -- shown as a reversible zone spanning the first three moves (both backends still exist, the flag can flip either direction) and a separate irreversible zone at retire. A dashed warning path shows what happens if retire is reached before a stop-the-old-system check passes: the fallback is gone and the migration quietly becomes a big-bang rewrite." caption="Figure 14.2 — The four moves, in order: intercept, route, incrementally replace, retire. The window stays reversible through move three; retire is the one move that closes it, and it only belongs last." %}

Notice what the metaphor rules out by construction. A strangler fig
never kills the host and then grows into the gap — it grows *around*
the host while the host is still alive, and the host only dies once
competing for the same resources has become impossible. The engineering
reading of that constraint is the entire case this book made in Chapter
4 for incremental migration over a rewrite: the system under
modernization keeps serving production traffic, keeps earning the
business revenue, for the full duration of the migration. There is
never a moment where the new system has to "catch up" to a frozen old
system's behavior, because the old system never stops, and there is
never a cutover event where everything moves at once, because nothing
in the pattern requires it. The whole migration is reversible, piece by
piece, right up until a given host limb is actually removed — which is
precisely what `examples/01-strangler-proxy/` demonstrates with one real
bounded context, start to finish, later in this chapter.

## Three variants of the same idea

Chapter 4 introduced the Strangler Fig family at the altitude a strategy
decision needs: name the three shapes the pattern takes, say which one
this book commits to, and move on. This chapter has running code to
stand the distinction up properly, because the three variants answer
two different questions — *how does the routing decision get
made*, and *does data ownership have to move in the same step as code
ownership* — and conflating them is a common way strangler migrations
get needlessly harder than they have to be.

**The Proxy variant** is the general case: a dedicated facade sits in
front of the old system, and every request passes through it before
reaching either backend. The facade is free to make its routing
decision on anything it can observe about the request — the path, a
header, a cookie, a percentage-based coin flip for canary traffic — and
nothing about the rule has to be the same for every piece of
functionality being migrated. `examples/01-strangler-proxy/` is a Proxy
in exactly this sense: it is a standalone Camel-on-Quarkus application,
entirely separate from both the monolith and any extracted service,
and every request this book's clients make — the Newman behavior-
equivalence suite included — goes through it first.

**The Redirection variant** is the Proxy variant with a specific,
narrower routing rule: route by URI path (or, for non-HTTP transports,
by whatever addressable resource identifier plays the same role). This
is the variant this book's proxy actually implements for the Review
cutover, and it is worth being precise about why that narrower rule is
the right one here rather than a missed opportunity for something
fancier. A path-based rule maps directly onto REST resource boundaries,
which means a migration can be staged resource by resource — exactly
the "one bounded context at a time" sequencing Chapter 13's coupling
ranking produced — without the proxy ever needing to understand
anything about request bodies, payment methods, or any other
business-level signal. The routing decision stays mechanical and
auditable: given a path, there is exactly one rule that decides the
backend, and that rule is readable in a handful of lines of route code,
not scattered across conditionals keyed on domain concepts that will
themselves change as the migration proceeds.

**The Shared-Database variant** answers the second question entirely:
does the newly extracted service need its own database before it can
go live, or can it keep reading and writing the same tables the
still-shrinking monolith uses while the *code* separation proceeds on
its own schedule? This book's own Review extraction uses exactly this
variant, and the Review service's own
`application.properties` says so in plain configuration —
`quarkus.hibernate-orm.schema-management.strategy=none` — because
`examples/02-review-service/` is still in its Phase A
(spring-compat-lift) form and owns no schema of its own yet. The
`reviews` table lives in the same shared Postgres instance the
monolith's five remaining contexts use, and nothing about the cutover
this chapter walks through required that to change first. That is not
a corner cut; it is a boundary separable from the service
boundary, and forcing both to move in the same step would have bought
this chapter's worked example nothing except a data migration it did
not yet need. True per-context data ownership for Review — its own
schema, its own database, no shared rows — is deferred to Chapter 18
and Chapter 19, the shared-data-to-owned-data arc, same as every other
context in this book's sequence.

These three variants are not mutually exclusive choices you pick once
for an entire migration. A single proxy can route by path for most
contexts and by a different signal for one that needs it; a single
extraction can go live behind a code cutover while its data cutover
happens two chapters later. What matters is naming which variant
answers which question at each seam, rather than treating "strangler
fig" as one undifferentiated technique and discovering mid-migration
that the code decision and the data decision were never actually the
same decision.

## The mechanics, as implemented here, in Camel

`StranglerProxyRoute.java` is a single Camel `RouteBuilder`, and its
entire job — intercept, decide, forward — fits in one `configure()`
method. Reading it top to bottom is reading the whole mechanism this
chapter has been describing in the abstract:

```java
from("platform-http:/api?matchOnUriPrefix=true")
    .routeId("strangler-fig-proxy")
    .log("strangler-proxy: ${header.CamelHttpMethod} ${header.CamelHttpPath}")

    .choice()
        .when(PredicateBuilder.and(
                simple("${header.CamelHttpPath} startsWith '/api/reviews'"),
                exchange -> reviewEnabled))
            .setProperty(TARGET_PROPERTY, constant(TARGET_REVIEW))
        .otherwise()
            .setProperty(TARGET_PROPERTY, constant(TARGET_MONOLITH))
    .end()

    .choice()
        .when(simple("${exchangeProperty." + TARGET_PROPERTY + "} == '" + TARGET_REVIEW + "'"))
            .to(reviewServiceBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
        .otherwise()
            .to(monolithBaseUrl + "?bridgeEndpoint=true&throwExceptionOnFailure=false")
    .end();
```

**The consumer, `platform-http:/api?matchOnUriPrefix=true`,** is the
"intercept" step made literal: every request under `/api/**` lands on
this one route, regardless of which bounded context it is ultimately
headed for. `matchOnUriPrefix=true` is what makes a single consumer
sufficient for an entire API surface rather than one consumer per
resource — without it, Camel would need an exact path match, and this
route would need to be rewritten every time a new resource appeared
anywhere in the system. The cost of that convenience is a fact that
tripped up this book's own cutover, covered in the next
section: `platform-http` hands the route the **full** incoming path in
`CamelHttpPath`, including the `/api` prefix the consumer itself was
registered under — not a path relative to that prefix, which is the
assumption an earlier version of this route's predicate made, incorrectly.

**The first `choice()` is the "route" step** — the decision point
Fowler's pattern requires to exist independently of either backend's
own code. It checks two things at once, combined with
`PredicateBuilder.and`: does the request path start with
`/api/reviews`, and is the `strangler.review.enabled` flag currently
on. Only when both hold does the exchange get tagged
`stranglerTarget=review`; every other request — including `/api/reviews`
traffic when the flag is off — gets tagged `stranglerTarget=monolith`.
Tagging the decision onto an exchange property, rather than branching
immediately into two separate `.to()` calls, is a small but deliberate
choice: it keeps the *decision* and the *action* in two separate,
independently readable blocks, which is exactly what let this book's
own cutover bug (next section) be diagnosed by reading the decision
logic in isolation from the forwarding logic.

**The flag itself, `strangler.review.enabled`,** is a plain
`@ConfigProperty boolean`, read from Quarkus/SmallRye Config, with a
default of `false`:

```java
@ConfigProperty(name = "strangler.review.enabled", defaultValue = "false")
boolean reviewEnabled;
```

Nothing about flipping this value touches the route's code. It is a
configuration entry — in `application.properties`, or overridden at
runtime with `-Dstrangler.review.enabled=true` or
`STRANGLER_REVIEW_ENABLED=true` — read once per request from CDI-managed
config, which is precisely what makes the cutover (and, for as long as
both backends exist, its reversal) a configuration change plus a
restart rather than a code change plus a redeploy. That distinction is
not a convenience detail; it is the entire reason a strangler migration
can be reversible at all. A routing decision baked into source code
requires a build, a review, and a deploy to reverse — three separate
chances for the reversal itself to fail. A routing decision read from
config requires none of that.

**The second `choice()` is the forward — the actual reverse proxy —**
and the two query parameters on each `.to()` call each do essential
work. `bridgeEndpoint=true` tells Camel's HTTP producer to
reuse the inbound request's method, path, and query string exactly as
received, rather than building a new URI from the Camel message the way
a plain HTTP producer call normally would; without it, every header,
every query parameter, and the HTTP verb itself would need to be copied
across manually, and any one of them missed would be an observable
difference between talking to the proxy and talking to a backend
directly — the one thing this pattern cannot tolerate.
`throwExceptionOnFailure=false` tells the same producer not to convert a
4xx or 5xx backend response into a thrown Camel exception, which by
default it would. Without that flag, a backend's `402 Payment Required`
or `404 Not Found` would arrive at the proxy as a `CamelExecutionException`
and get turned into a generic Camel error response instead of the real
status code and body the backend actually sent — which would be a
difference the behavior-equivalence suite would catch immediately, since
its assertions check exact status codes and response bodies, not just
"did the request succeed."

**One more property is worth naming because it is a security property,
not just a mechanical one:** the target base URLs —
`strangler.monolith.base-url` and `strangler.review.base-url` — are
fixed, operator-configured constants read from config, never built from
anything in the request itself. The route's `choice()` picks *which* of
two known, already-configured URIs to use; it never interpolates a
header or a path segment into the destination URI. That is the
secure-by-default discipline a dynamic-URI Camel route needs: nothing
an attacker controls in a request can redirect the proxy to an
arbitrary host, because the set of possible destinations was decided at
deploy time, not at request time.

## The reversibility window: a bug the equivalence suite almost missed

The clean route listing above is the *fixed* version. The actual
cutover attempt for Review did not go cleanly on the first try, and the
failure is worth telling in full, because it is the single best
illustration in this book of why "the tests passed" and "the cutover
is correct" are not the same claim.

With the flag at its original default of `false`, the full
behavior-equivalence suite — sixteen requests, forty-nine assertions,
captured once against the running monolith and never edited since — was
run unchanged through the proxy, and came back green: 49 of 49
assertions passed. That much was expected and correct: Review was
served by the monolith, reached through the proxy, and the proxy
introduced no observable difference.

The next step — flip `strangler.review.enabled` to `true` and expect
Review traffic to now reach the extracted Quarkus service instead — also
came back 49 of 49 green. That result was **wrong**, and nobody caught
it from the suite result alone. The route's content-based predicate
checked `${header.CamelHttpPath} startsWith '/reviews'`, which never
matches an incoming path of `/api/reviews`, because — as the previous
section already named — `platform-http` hands the route the *full*
path, prefix included. Every request, flag on or flag off, fell through
to the `otherwise()` branch and reached the monolith. The flag was
being read correctly; the predicate built around it was simply checking
the wrong string.

What let that bug hide behind a fully green suite is the shared-database
variant discussed two sections ago: `examples/02-review-service/` reads
and writes the exact same `reviews` table, in the exact same Postgres
database, that the monolith's own Review module used. Two different
processes serving identical rows from one shared table *is* the same
data no matter which process actually answered the HTTP request, so a
black-box contract test that only ever checks status codes and response
bodies has no way to distinguish "the correct new service answered this
request" from "the wrong old backend answered it with identical data."
The equivalence suite's forty-nine assertions were never wrong on their
own terms — they are just not a sufficient test of *routing correctness*
when both candidate backends can produce byte-identical answers.

The check that actually surfaced the bug was simpler and more
decisive than any additional assertion added to the suite: stop the
monolith entirely, and retry the same request through the proxy. A
correctly wired cutover should keep working — the Review service is a
separate, still-running process that does not depend on the monolith
being up. Instead, the request failed the exact same way every other
`/api/**` path did with the monolith down, which proved, independent of
any response body, that Review traffic was still reaching a now-dead
monolith regardless of the flag. That is a stronger proof than a
status-code comparison ever could be, because it tests the proxy's
actual *routing decision*, not merely whether the two backends happen
to agree. Once the predicate was corrected to check
`startsWith '/api/reviews'` — matching the path shape `platform-http`
actually presents — the same stop-the-monolith check came back exactly
as it should: Review requests succeeded (served by the now-isolated
Quarkus service), and every other path failed (correctly, with nothing
left to serve it).

This is the "reversibility window" this chapter's whole mechanism is
built to provide, demonstrated with a real failure inside it rather
than asserted as a property on faith: for as long as both the
monolith's Review module and the extracted Review service exist, the
flag can move in either direction, and — once the predicate bug was
fixed — both directions were independently proven against the full
suite, with the monolith still intact as a working fallback the entire
time. Nothing about discovering and fixing the predicate bug required
touching the monolith at all; the whole repair happened inside the
proxy, which is precisely the containment a strangler seam is supposed
to buy you.

{% include codetabs.html langs="Before the cutover (flag=false)|After the cutover (flag=true, routing fixed)" %}

```bash
# Flag off: every request, including Review, reaches the monolith.
curl -s localhost:8888/api/reviews?sku=SKU-WIDGET-001   # 200 — monolith's Review module
curl -s localhost:8888/api/orders                       # 200 — monolith (unaffected)
```

```bash
# Flag on, monolith stopped entirely, to prove the routing decision itself:
curl -s localhost:8888/api/reviews?sku=SKU-WIDGET-001   # 200 — review-service; monolith is down
curl -s localhost:8888/api/orders                       # 500 — still targets the dead monolith, as expected
```

## Decommission: the one deliberate, irreversible step

Reversibility is a real property of this pattern, but it is not an
unbounded one, and the Review extraction's final step is exactly where
that boundary sits. Once the routing fix above was verified, the
monolith's Review module — its controller, service, repository, entity,
and DTOs, plus the tests written against them — was deleted outright
from `examples/00-monolith/`. The monolith's security configuration was
deliberately left in place rather than cleaned up alongside it: with no
route left in the monolith matching `/api/reviews`, the one
authenticated rule it used to enforce simply never fires anymore,
rather than being deleted and risking disturbing something unrelated —
a scope decision that kept the change confined to Review's own files.
The `reviews` table itself, and its seed data, were *not*
dropped, for exactly the shared-database reason named earlier: the
extracted service still depends on that table being there, and true
data ownership is a Chapter 18/19 problem, not a Review-chapter one.
With the module gone, `strangler.review.enabled=true` became the
committed default in `application.properties`, replacing the `false`
it had held throughout the cutover check.

The proof that this step is irreversible, and not merely
inconvenient to reverse, is a direct request against the now-slimmed
monolith: `GET http://localhost:8080/api/reviews` returns `404 Not
Found`, while `GET http://localhost:8080/api/orders` returns `200 OK`,
unaffected. As a final sanity check, the proxy was even restarted with
an explicit override, `-Dstrangler.review.enabled=false`, forcing the
flag back to the state that used to mean "serve Review from the
monolith." The flag still mechanically works — it is not dead code —
but a request to `/api/reviews` through it now returns `404`, because
the backend that value used to select no longer has anything to serve.
That is the observable shape of a closed reversibility
window: the mechanism that provided reversibility is still there and
still functions exactly as designed; what changed is that one of the
two destinations it could point to has been permanently removed.

Decommission as the *last* step, after
every other check has gone green, is the whole discipline this chapter
has been building toward. Retiring the old code too early — before the
routing fix was verified, for instance — would have destroyed the
fallback that made diagnosing and correcting the predicate bug cheap
and safe. Retiring it only once the stop-the-monolith check, not just
the suite, had proven the new service was answering is what
keeps "incremental" from quietly becoming "big-bang, just spread over
more calendar time."

## What you learned

- Fowler's strangler fig pattern is four ordered moves — intercept,
  route, incrementally replace, retire — and the ordering is the point:
  retiring the old code before the new code's cutover is actually
  proven is the single most common way an "incremental" migration loses
  the safety property that justified choosing it.
- The Proxy, Redirection, and Shared-Database variants answer two
  different questions — how the routing decision gets made, and whether
  data ownership has to move in the same step as code ownership — and
  this book's Review extraction uses the Redirection variant for
  routing (by URI path) and the Shared-Database variant for data (the
  `reviews` table stays in the monolith's schema until Chapters 18–19).
- `bridgeEndpoint=true` and `throwExceptionOnFailure=false` on the
  Camel HTTP producer are what make a reverse proxy transparent enough
  to pass a behavior-equivalence suite: method, path, query, and the
  backend's real status code and body all have to survive the hop
  unchanged, or the suite will see the difference.
- A feature flag read from config, not baked into route code, is what
  makes a cutover (and its reversal) a restart instead of a redeploy —
  and a shared database between two backends can make a broken cutover
  look like a working one, which is why this book's own cutover trusted
  a stop-the-backend check over a green suite result before declaring
  victory.

The proxy and its flag are infrastructure; they do not, on their own,
produce a working Review service. **Chapter 15, "Extraction 1 — Review
Service,"** walks that service into existence end to end — the Quarkus
scaffold, the HTTP Basic-secured endpoints, the equivalence-gate pass, and the
flag-gated cutover this chapter has been describing from the proxy's
side of the seam. **Chapter 16, "Content-Based Routing & the
Anti-Corruption Layer,"** goes back inside this same route and the ones
that follow it to build the translation layer — the message translator
and content enricher — that keeps a legacy backend's leaky data shapes
from leaking into every service that has to talk to it. And **Chapter
17, "Extraction 2 — Notification Service,"** is where this pattern
meets its first real complication: an event consumer that cannot simply
be reached by routing an inbound HTTP path, which is exactly the seam
that forces this book's outbox and its first taste of event-driven
cutover.

---
*Verification status: <span class="status status--unverified">unverified</span>
for the purposes of this chapter's own claims — the three Newman suite
runs, the predicate bug, and the decommission sequence this chapter
narrates are drawn directly from `examples/01-strangler-proxy/CUTOVER.md`
and `examples/01-strangler-proxy/README.md`, both independently
checkable by reading those files and by rebuilding and running the
proxy per its `README.md` ("Running it"). What this chapter adds beyond
that record — the Fowler/strangler-variant framing and the line-by-line
reading of `StranglerProxyRoute.java` — has not itself been re-run
against the live proxy during authoring and should be spot-checked
against the current route source before being treated as a drop-in
substitute for reading the code directly.*
