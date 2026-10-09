// deck.js — "Modernizing Enterprise Applications" 201 · Quarkus & Camel deep-dive.
// Red Hat house style, 16:9. Build: NODE_PATH=./node_modules node deck.js
"use strict";

const H = require("./deck-helpers.js");
const {
  COLOR, FONT, W, PNG, ASSETS,
  newDeck, addFooter, addContentTitle, addBullets, addTwoColBullets,
  addStatusTable, addCaption, addCodeSlide, addSectionDivider, addNotes,
} = H;

const OUT = "Modernizing-201-Quarkus-Camel-r1.0.pptx";
const REV = "r1.0";

const pres = newDeck();
pres.title = "Modernizing Enterprise Applications — 201 · Quarkus & Camel in depth";
let pageNum = 0;

function S() { const s = pres.addSlide(); pageNum += 1; addFooter(s, pageNum); return s; }
function divider(code, title, subtitle, notes) {
  const s = pres.addSlide(); pageNum += 1; addSectionDivider(s, code, title, subtitle); addNotes(s, notes);
}

// ---- local helper: bold-lead bullets (bold subject run + normal text run) ----
function leadBullets(slide, items, opts = {}) {
  const x = opts.x ?? 0.62, y = opts.y ?? 1.95, w = opts.w ?? 12.09, h = opts.h ?? 4.7;
  const fontSize = opts.fontSize ?? 16;
  const runs = [];
  items.forEach((b) => {
    const lvl = b.lvl || 0;
    const para = {
      fontFace: FONT.body, fontSize: lvl ? fontSize - 1 : fontSize,
      bullet: { code: lvl ? "25E6" : "25CF", indent: 18 }, indentLevel: lvl,
      paraSpaceBefore: lvl ? 0 : 4, paraSpaceAfter: lvl ? 7 : 13,
    };
    const color = b.color || (lvl ? COLOR.caption : COLOR.body);
    if (b.lead) {
      runs.push({ text: b.lead, options: { ...para, bold: true, color: COLOR.ink } });
      runs.push({ text: (b.sep === undefined ? " — " : b.sep) + b.text, options: { fontFace: FONT.body, fontSize: para.fontSize, color, breakLine: true } });
    } else {
      runs.push({ text: b.text, options: { ...para, color, breakLine: true } });
    }
  });
  slide.addText(runs, { x, y, w, h, valign: "top", margin: 0, lineSpacingMultiple: 1.12 });
}

function leadSlide(eyebrow, title, items, notes, opts) {
  const s = S(); addContentTitle(s, eyebrow, title); leadBullets(s, items, opts || {}); addNotes(s, notes); return s;
}

// ---- local helper: diagram left (~55%), bold-lead bullets right ----
function diagramBulletsSlide(eyebrow, title, png, items, notes) {
  const s = S();
  addContentTitle(s, eyebrow, title);
  const maxW = 6.55, maxH = 4.55, x0 = 0.62, y0 = 1.95;
  s.addImage({ path: `${PNG}/${png}.png`, x: x0, y: y0, w: maxW, h: maxH, sizing: { type: "contain", w: maxW, h: maxH } });
  const bx = x0 + maxW + 0.35;
  leadBullets(s, items, { x: bx, y: 2.0, w: W - 0.62 - bx, h: 4.55, fontSize: 14 });
  addNotes(s, notes);
  return s;
}

function codeSlide(eyebrow, title, lang, lines, caption, notes, opts) {
  const s = S(); addCodeSlide(s, eyebrow, title, lang, lines, caption, opts || {}); addNotes(s, notes); return s;
}

// ===== COVER =================================================================
{
  const s = pres.addSlide(); pageNum += 1;
  s.background = { color: COLOR.white };
  try { s.addImage({ path: `${ASSETS}/cover-panel.png`, x: 0, y: 0, w: W, h: 7.5 }); } catch (e) {}
  s.addText("MODERNIZING ENTERPRISE APPLICATIONS · 201 · QUARKUS & CAMEL", { x: 6.00, y: 1.86, w: 6.95, h: 0.60,
    fontFace: FONT.title, fontSize: 13, bold: true, color: COLOR.red, charSpacing: 3, align: "left", valign: "top" });
  s.addText([{ text: "Quarkus & Camel,", options: { breakLine: true } }, { text: "in depth" }], {
    x: 5.95, y: 2.48, w: 6.95, h: 2.00, fontFace: FONT.title, fontSize: 50, bold: true, color: COLOR.ink, align: "left", valign: "top" });
  s.addText("The runtime and the integration layer up close: the extensions, the routes, the EIPs, and the dev loop behind the modernized system.", {
    x: 6.00, y: 4.55, w: 6.75, h: 1.10, fontFace: FONT.body, fontSize: 17, italic: true, color: COLOR.caption, align: "left", valign: "top" });
  s.addText(REV, { x: 11.85, y: 5.95, w: 0.95, h: 0.30, fontFace: FONT.mono, fontSize: 11, color: COLOR.caption, align: "right", valign: "middle" });
  try { s.addImage({ path: `${ASSETS}/logo-candidate-2.png`, x: 11.10, y: 6.78, w: 1.55, h: 0.37 }); } catch (e) {}
  addNotes(s, "This is the third deck in the set and the most code-forward. The 101 makes the case for modernizing; the general 201 walks the whole migration. This one answers a narrower question: how do the two runtimes actually make it work? Quarkus runs all six services and the gateway; Apache Camel is the integration layer — the edge router out front and the orchestrated saga inside shipping. We assume the audience knows the migration story and wants the routes, the extensions, and the dev loop. Everything shown is real code from the repository, not pseudocode.");
}

// ===== ORIENTATION ===========================================================
diagramBulletsSlide("ORIENTATION · TWO RUNTIMES", "Quarkus runs the services; Camel is the glue", "r-quarkus-camel-map",
  [
    { lead: "Quarkus", text: "runs every service — order, inventory, payment, shipping, notification, review, and the GraphQL gateway." },
    { lead: "Camel", text: "is the integration layer: the content-based edge router out front, and the Saga EIP orchestrator inside shipping." },
    { lead: "Camel on Quarkus", text: "the routes build at image time and run in the same fast, native-ready process — not a separate integration server." },
    { lead: "One mental model", text: "services hold business logic; routes move and coordinate messages between them." },
  ],
  "The whole deck sits on this one picture. Quarkus is the application runtime — every service is a Quarkus app. Camel is not a separate box; camel-quarkus runs inside a Quarkus process, so the edge router and the shipping saga are themselves Quarkus apps that happen to be built from Camel routes. That matters because the routes inherit everything Quarkus gives a service: build-time wiring, fast startup, native image, Dev Services. The division of labour is clean — services do the work, Camel routes move and sequence the messages between them — and it is the lens for everything that follows.");

leadSlide("ORIENTATION · SCOPE", "What this deck assumes",
  [
    { lead: "You know the story", text: "a Spring Boot monolith strangled into six Quarkus services, one seam at a time — that is the 101 and the general 201." },
    { lead: "This is the framework lens", text: "the routes, the extensions, the messaging wiring, and the inner dev loop, in code." },
    { lead: "Grounded in the repository", text: "every snippet is lifted from a real example module, not written for the slide." },
    { lead: "Two runtimes, one register", text: "Camel first (the integration layer), then Quarkus (the chassis and the services), then how they run together." },
  ],
  "A quick framing so expectations are right. This deck does not re-argue why to modernize or re-walk the six extractions — the other two decks do that. It assumes you have seen the arc and want to know how Quarkus and Camel deliver it. The plan is three acts: Camel as the integration layer, then Quarkus as the chassis and the services, then the two together — topology, telemetry, and which service demonstrates which capability. The appendix carries reference tables to keep open in another window. Code slides are real; comments in green are the parts worth reading aloud.");

diagramBulletsSlide("ORIENTATION · THE CORE IDEA", "Build-time, not boot-time", "r-build-time-vs-runtime",
  [
    { lead: "Traditional frameworks", text: "scan the classpath, build the metamodel, and generate proxies at every boot — slow start, large heap." },
    { lead: "Quarkus", text: "does that work once, at build time, and bakes the result into the artifact." },
    { lead: "The runtime just runs", text: "almost no startup reflection — which is what makes sub-second starts and native images possible." },
    { lead: "Why it matters here", text: "fast feedback in dev and cheap, dense services in production — the same code, moved left." },
  ],
  "This is the single idea that explains most of what Quarkus does differently, so it comes before the detail. A traditional JVM framework repeats a lot of work on every launch: scanning annotations by reflection, building its object graph, wiring ORM and configuration, generating dynamic proxies. Quarkus moves that to build time — an augmentation step that scans, wires, and proxies once and freezes the result into the artifact. The runtime then does almost no reflection, which is why startup drops to a fraction of a second and why a native image is even feasible. Keep this in mind for the native and dev-loop slides later.");

leadSlide("ORIENTATION · RECAP", "The migration in one slide",
  [
    { lead: "Start", text: "a Spring Boot monolith — six bounded contexts sharing one schema and one process." },
    { lead: "Strangle", text: "a Camel edge router peels off one context at a time onto Quarkus, reversibly, behind the behavior-equivalence suite." },
    { lead: "Own the data", text: "each extracted service takes its own schema; events flow over Kafka through the transactional outbox." },
    { lead: "End state", text: "six Quarkus services plus a GraphQL gateway; the monolith is decommissioned and frozen in the repo." },
  ],
  "A one-slide recap so this deck stands on its own if someone joins here. The system began as a single Spring Boot application whose six contexts shared one database — the classic coupling the migration unwinds. A Camel edge router sat in front and let each context be moved onto Quarkus one at a time, with the move provable and reversible because the behavior-equivalence suite had to stay green across every cutover. As each context moved it took ownership of its own schema, and cross-service communication shifted to Kafka events published through the transactional outbox. The end state is six Quarkus services and a GraphQL gateway, with the monolith decommissioned and kept only as a reference. What follows is how the two runtimes made that happen.");

// ===== SECTION A — CAMEL =====================================================
divider("A", "Camel — the integration layer", "Camel-on-Quarkus: routes, EIPs, and one orchestrated saga",
  "Act one is Camel. We cover how Camel runs on Quarkus, the strangler edge as a content-based router, the ACL call, the Enterprise Integration Patterns actually in play, and the orchestrated shipping saga built with the Camel Saga EIP. The throughline: Camel is where messages move and work is sequenced, expressed as readable routes rather than scattered glue code.");

leadSlide("CAMEL · ON QUARKUS", "Camel runs inside a Quarkus process",
  [
    { lead: "camel-quarkus-platform-http", text: "the edge router's HTTP consumer — the one front door on :8888." },
    { lead: "camel-quarkus-http", text: "the producer that forwards each request to the chosen service." },
    { lead: "camel-quarkus-saga / -direct / -bean", text: "the shipping orchestrator: the Saga EIP, in-process step endpoints, and bean delegation." },
    { lead: "Built at image time", text: "routes are wired during augmentation — so a Camel app starts as fast and goes native as readily as any other Quarkus service." },
    { lead: "No integration server", text: "there is no separate ESB or runtime to operate; a route is just a Quarkus app." },
  ],
  "Camel-on-Quarkus is the Quarkiverse packaging of Apache Camel as Quarkus extensions. The practical consequences are what matter here. First, dependencies are ordinary extensions: the edge router adds camel-quarkus-platform-http and -http; the shipping orchestrator adds -saga, -direct, and -bean. Second, routes are discovered and wired at build time like everything else in Quarkus, so a Camel app gets the same sub-second startup and native-image path as a plain service — no warm-up penalty for using Camel. Third, there is no separate integration server to run: each route set is just another Quarkus process in the topology. That is why the edge router and the saga sit comfortably beside the six services.");

codeSlide("CAMEL · EDGE ROUTER", "The strangler edge as a content-based router", "java · Apache Camel",
  [
    "from(\"platform-http:/api?matchOnUriPrefix=true\")",
    "    .routeId(\"edge-router\")",
    "    .choice()",
    "        .when(simple(\"${header.CamelHttpPath} startsWith '/api/reviews'\"))",
    "            .to(reviewServiceBaseUrl + \"?bridgeEndpoint=true&throwExceptionOnFailure=false\")",
    "        .when(simple(\"${header.CamelHttpPath} startsWith '/api/orders'\"))",
    "            .to(orderServiceBaseUrl + \"?bridgeEndpoint=true&throwExceptionOnFailure=false\")",
    "        // ... one when() per extracted context ...",
    "        .otherwise()",
    "            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))",
    "            .setBody(constant(\"{\\\"error\\\":\\\"NOT_FOUND\\\"}\"))",
    "    .end();",
  ],
  "The edge router — StranglerProxyRoute",
  "This is the Content-Based Router EIP, and it is the whole strangler seam in one route. Every request lands on the single platform-http consumer, and choice() dispatches by URI path prefix to the matching service's base URL. Two option flags carry the semantics: bridgeEndpoint=true makes the HTTP producer reuse the inbound method, path, query, and body unchanged, so the proxy is transparent; throwExceptionOnFailure=false lets a real 4xx or 5xx from a service flow straight back to the caller instead of becoming a Camel exception — exactly what the contract suite needs to see. During the migration each branch consulted a per-context feature flag; now that all six contexts are extracted the flags are retired and the routing is unconditional, with an unmatched path answered by a direct 404 since there is no monolith left to fall back to.");

leadSlide("CAMEL · THE ACL CALL", "The anti-corruption layer you did not need to build",
  [
    { lead: "The edge does not translate", text: "each branch is a transparent reverse proxy — the request passes through byte-for-byte." },
    { lead: "Because the contracts matched", text: "the extracted services kept the monolith's DTOs field-for-field, so a translator here would be a no-op." },
    { lead: "The real ACL is at the gRPC seam", text: "inventory.proto's vocabulary (stock_keeping_unit, on_hand_qty) is translated by the client and server, where the contracts actually differ." },
    { lead: "Avoid speculative glue", text: "a no-op translator fabricated for a contract that does not differ is cost without benefit." },
  ],
  "A common instinct is to put an anti-corruption layer at every seam. The repository does not do that. At the HTTP edge, the extracted services were lifted with their DTOs unchanged — same field names, types, and order — so the JSON on the wire is identical to the monolith's. A Camel message translator there would have nothing to translate; it would be ceremony. So every edge branch is a transparent proxy. The real anti-corruption layer lives where the contracts actually diverge: the order-to-inventory call over gRPC, where inventory.proto uses a different vocabulary than the internal model, translated on the client and server sides. The lesson is to build the ACL where there is a real mismatch, not everywhere by reflex.");

{
  const s = S();
  addContentTitle(s, "CAMEL · EIPs", "The Enterprise Integration Patterns in play");
  addStatusTable(s, [
    { code: "CBR", name: "Content-Based Router", purpose: "The edge router dispatches by URI path prefix to the owning service." },
    { code: "Translator", name: "Message Translator / ACL", purpose: "Only at the gRPC inventory seam, where the wire vocabulary differs." },
    { code: "Outbox", name: "Transactional Outbox", purpose: "Each service writes events to a table; a scheduled relay publishes them." },
    { code: "Saga", name: "Saga + Compensation", purpose: "Shipping orchestrates dispatch with a compensating action on failure." },
    { code: "Idemp.", name: "Idempotent Consumer", purpose: "Kafka handlers tolerate at-least-once delivery, keyed by business id." },
    { code: "DLC", name: "Dead Letter / retry", purpose: "At-least-once relay with retry; poison messages isolated, not lost." },
  ], { colW: [1.60, 3.20, 7.29] });
  addNotes(s, "Rather than name-drop patterns, this maps each one to where it actually appears in the code. The Content-Based Router is the edge. The Message Translator is scoped to the single gRPC seam where contracts differ. The Transactional Outbox appears in every event-producing service — the write and the event commit in one local transaction, and a scheduled relay publishes afterward. The Saga with compensation is the shipping orchestrator, covered next. The Idempotent Consumer is how every Kafka handler stays correct under at-least-once delivery. And retry-with-dead-letter keeps the relay durable. The point of the table is that these are not decorations; each does real work in a specific file.");
}

codeSlide("CAMEL · SAGA EIP", "The orchestrated saga, as one readable route", "java · Camel Saga EIP",
  [
    "from(ShippingService.SAGA_START_ENDPOINT)",
    "    .routeId(\"shipping-saga\")",
    "    .saga()",
    "        .sagaService(\"inMemorySagaService\")",
    "        .propagation(SagaPropagation.REQUIRES_NEW)",
    "        .completionMode(SagaCompletionMode.AUTO)",
    "        .timeout(15, TimeUnit.SECONDS)",
    "        .compensation(\"direct:ship-compensate\")",
    "        .option(\"orderId\", header(\"orderId\"))",
    "    .to(\"direct:ship-enrich\")        // step 1",
    "    .to(\"direct:ship-dispatch\")      // step 2 — persists before step 3 can fail",
    "    .to(\"direct:ship-book-carrier\")  // step 3 — the deterministic failure point",
    "    .to(\"direct:ship-emit-dispatched\")// step 4 — only on success",
    "    .end();",
  ],
  "The shipping orchestrator — ShipmentSagaRoute",
  "This is the counterpart to the choreographed payment saga. Here one route sequences the fulfilment steps top to bottom and names a single compensating action. The .saga() block configures a coordinator: AUTO completion means the coordinator watches the wrapped step chain and, if any step throws or the fifteen-second timeout elapses, invokes the one registered compensation endpoint exactly once — Camel's saga SPI guarantees a given instance is compensated or completed, never both. The steps are plain direct: endpoints, each delegating to one method on a bean, which keeps the route readable and the step logic unit-testable. Compensation correlates by orderId, carried as a saga option, rather than a saved shipmentId — so it works even if the failure happened before an id was assigned. Readability is the feature: the whole workflow is one screen.");

leadSlide("CAMEL · COMPENSATION", "One compensation, at most once",
  [
    { lead: "AUTO completion mode", text: "the coordinator decides success or failure from the step chain — no manual complete/compensate calls." },
    { lead: "Any failure triggers it", text: "a thrown step or an elapsed timeout runs direct:ship-compensate once." },
    { lead: "Correlate by business id", text: "compensation keys on orderId, so it works even before a shipmentId exists." },
    { lead: "Ordering is intentional", text: "dispatch persists before the carrier call can throw, so there is always state to compensate." },
    { lead: "The failure still propagates", text: "after compensating, the original exception reaches the caller, which logs rather than crashes the consumer." },
  ],
  "Compensation is where orchestrated sagas prove their worth, so it gets its own slide. The coordinator in AUTO mode removes a whole class of bugs: there are no hand-written complete or compensate calls to forget. Any abnormal outcome — an exception from a step or a timeout — runs the single compensation route, exactly once, guaranteed by the SPI. Two design choices make it reliable. Compensation correlates by orderId, a business identifier that exists from the start, rather than a technical id that might not be assigned when a failure hits. And the step ordering is chosen so the shipment is persisted before the carrier-booking step that can fail, so compensation always has concrete state to undo. Finally, the original failure is re-thrown and logged by the service, so one failed saga does not wedge the Kafka consumer.");

diagramBulletsSlide("CAMEL · TWO STYLES", "Camel orchestrates, Kafka choreographs", "r07-choreo-vs-orchestr",
  [
    { lead: "Choreographed (payment)", text: "services react to each other's Kafka events with no coordinator — decoupled, resilient, diffuse." },
    { lead: "Orchestrated (shipping)", text: "one Camel route sequences the steps and owns the compensation — legible, central, in the critical path." },
    { lead: "Same domain, both styles", text: "the repository shows them side by side on the same checkout, side by side." },
    { lead: "The choice is per workflow", text: "there is no single correct style — pick by who should own the sequence." },
  ],
  "The repository shows both coordination styles on the same checkout, which is the clearest way to teach the trade-off. Payment is choreographed: the order service emits an event, payment reacts and emits its own, and nobody holds the whole sequence — it is loosely coupled and resilient, but no single place tells you what the workflow is. Shipping is orchestrated with the Camel Saga EIP: one route is the sequence, and it owns compensation — legible and centralized, at the cost of putting a coordinator in the critical path. Camel is comfortable doing both — it speaks Kafka for the choreographed hops and expresses the orchestrated one as a route. The lesson is that this is a per-workflow decision, not a house style.");

{
  const s = S();
  addContentTitle(s, "CAMEL · COMPONENTS", "The Camel components in use");
  addStatusTable(s, [
    { code: "platform-http", name: "Edge consumer", purpose: "The single /api front door; matchOnUriPrefix routes the whole path space." },
    { code: "http", name: "Service producer", purpose: "Forwards each request to the chosen service; bridgeEndpoint keeps it transparent." },
    { code: "direct", name: "In-process steps", purpose: "The saga's step endpoints — synchronous, in-JVM, zero serialization." },
    { code: "bean", name: "Step delegation", purpose: "Each direct: step calls one method on a CDI bean, kept unit-testable." },
    { code: "saga", name: "Saga coordinator", purpose: "The Saga EIP SPI; InMemorySagaService for this single-JVM demonstrator." },
  ], { colW: [2.10, 2.60, 7.39] });
  addNotes(s, "A short reference for the components the two routes actually use. platform-http is the edge consumer — one endpoint catching the whole /api path space via matchOnUriPrefix. http is the producer that forwards to a service; its bridgeEndpoint option is what makes the proxy transparent. direct gives the saga its in-process, synchronous step endpoints with no serialization between steps. bean delegates each step to a single method on a CDI bean, which keeps route logic out of the steps and the steps independently testable. saga is the coordinator SPI; this demonstrator uses the InMemorySagaService implementation, which is correct for one JVM — a production deployment would swap in a durable coordinator, named in the appendix and in the general 201's deferral list.");
}

leadSlide("CAMEL · THE FRONT DOOR", "One consumer for the whole path space",
  [
    { lead: "platform-http:/api?matchOnUriPrefix=true", text: "a single consumer catches every path under /api, not one route per endpoint." },
    { lead: "The full path is the key", text: "the router matches on the complete CamelHttpPath (e.g. /api/reviews/5), not a route-relative suffix." },
    { lead: "A hard-won detail", text: "a route-relative prefix silently never matches — a real lesson captured in the cutover notes." },
    { lead: "Secure by default", text: "the target is chosen by path prefix alone, never by a value taken from the request." },
  ],
  "Before the code, the shape of the edge: one platform-http consumer with matchOnUriPrefix catches the entire /api path space, so adding a context is a new when() clause rather than a new consumer. The matching detail matters and cost real debugging time on the project: platform-http hands the route the full incoming path, so the guards match on the complete CamelHttpPath like /api/reviews/5, not a path relative to the route's own /api prefix — a route-relative '/reviews' guard silently never fires, a lesson the cutover notes record. One security property matters here: the backend is selected by matching a fixed path prefix, never by interpolating a value from the request into the target URI, which keeps the router free of server-side request-forgery risk by construction.");

codeSlide("CAMEL · TRANSPARENCY", "Transparent by construction: bridgeEndpoint", "java · Apache Camel",
  [
    ".to(orderServiceBaseUrl + \"?bridgeEndpoint=true&throwExceptionOnFailure=false\")",
    "",
    "// bridgeEndpoint=true",
    "//   reuse the inbound method, path, query, headers, and body as-is",
    "//   -> the proxy adds nothing and removes nothing",
    "",
    "// throwExceptionOnFailure=false",
    "//   a 4xx / 5xx from the service flows back unchanged",
    "//   -> the caller sees the real status, not a Camel error",
  ],
  "Two options make the edge a faithful pass-through",
  "This is one line from the edge router, pulled out because two option flags carry the whole transparency contract. bridgeEndpoint=true tells the HTTP producer to reuse the inbound request wholesale — method, path, query string, headers, and body pass through untouched — so the proxy introduces no observable difference versus talking to the service directly. throwExceptionOnFailure=false stops Camel from converting a 4xx or 5xx service response into a thrown exception; instead the real status and body flow straight back to the caller. Together they are why the behavior-equivalence suite could run through the proxy and see byte-for-byte the same responses as the direct calls — transparency is a property of these two flags, not an aspiration.");

leadSlide("CAMEL · TESTING ROUTES", "Routes are tested like any other code",
  [
    { lead: "MockEndpoint", text: "assert that a message reached the expected endpoint with the expected body — the edge router's routing test." },
    { lead: "adviceWith", text: "rewrite a route's endpoints at test time to stub externals — used to exercise the saga's compensation leg." },
    { lead: "On Dev Services", text: "integration tests get a throwaway Postgres and Kafka automatically — no hand-run infrastructure." },
    { lead: "Same gate as the services", text: "route tests run in the same CI as the behavior-equivalence suite." },
  ],
  "Camel routes are not second-class code, and the repository tests them as first-class. Two Camel testing tools do most of the work. MockEndpoint lets a test assert that a message arrived at a given endpoint with the expected content — the edge router's routing test uses it to prove each path lands on the right backend. adviceWith rewrites a route's endpoints at test time, which is how the shipping saga's compensation path is driven deterministically: stub the step that should fail, then assert the compensation route ran exactly once. Because these are Quarkus apps, the integration tests ride on Dev Services — a throwaway Postgres and Kafka appear automatically — and they run in the same CI pipeline as the behavior-equivalence suite, so a broken route fails the build like anything else.");

// ===== SECTION B — QUARKUS ===================================================
divider("B", "Quarkus — the chassis & the services", "Extensions as capabilities: config, data, messaging, gRPC, GraphQL, native",
  "Act two is Quarkus. The theme is extensions-as-capabilities: config, health, persistence, messaging, gRPC, GraphQL, and security each arrive as a dependency rather than framework code you write and maintain. We walk the real code for each, cover the two-phase migration that got services onto Quarkus, the inner dev loop, and the native-image payoff that the build-time model unlocks.");

diagramBulletsSlide("QUARKUS · THE CHASSIS", "The chassis is a set of extensions", "r08-chassis",
  [
    { lead: "MicroProfile Config", text: "type-safe configuration injected with @ConfigProperty — no config plumbing." },
    { lead: "SmallRye Health", text: "add the extension and /q/health/live and /q/health/ready appear automatically." },
    { lead: "Micrometer Metrics", text: "a metrics endpoint and JVM/HTTP meters, by dependency." },
    { lead: "SmallRye Fault Tolerance", text: "@Retry, @Timeout, @CircuitBreaker as annotations, used where a real failure earns them." },
    { lead: "Opt-in, not framework", text: "a capability becomes the service's the moment the extension is on the classpath." },
  ],
  "The microservice chassis — the cross-cutting foundation every service needs — is, in Quarkus, something you acquire by declaring dependencies rather than something a team builds. Configuration is MicroProfile Config: values injected type-safely with @ConfigProperty, with defaults, no boilerplate. Health is one extension that publishes the liveness and readiness endpoints Kubernetes probes read. Metrics is Micrometer, again by dependency. Fault tolerance is SmallRye, offering retry, timeout, and circuit-breaker as annotations — used in this project only where a real failure mode calls for them, notably the gRPC deadline, with the circuit breaker left out. The deeper point, which recurs on the native and tracing slides, is that an extension on the classpath turns a capability on with little or no code of your own.");

codeSlide("QUARKUS · CONFIG & HEALTH", "Config and health, by declaration", "properties · java",
  [
    "# application.properties — configuration is just keys",
    "quarkus.http.port=8087",
    "quarkus.flyway.migrate-at-start=true",
    "inventory.grpc.timeout-ms=5000",
    "",
    "// inject it anywhere, type-safe, with a default:",
    "@ConfigProperty(name = \"inventory.grpc.timeout-ms\", defaultValue = \"5000\")",
    "long timeoutMs;",
    "",
    "// health is an extension, not code you write:",
    "//   add quarkus-smallrye-health  ->",
    "//   GET /q/health/live   and   /q/health/ready   appear automatically",
  ],
  "Config and health across the services",
  "This is the chassis idea made concrete in the smallest possible code. Configuration lives as plain keys in application.properties — the HTTP port, whether Flyway migrates at startup, a gRPC timeout — and is injected wherever it is needed with @ConfigProperty, type-safely and with a default so a missing key is not a crash. There is no configuration-loading code to write or test. Health is even less: adding the quarkus-smallrye-health extension publishes the liveness and readiness endpoints that the Kubernetes probes in the deploy tree already point at — the service did not write a health controller. Across the six services these two lines of habit replace what would otherwise be a shared internal library.");

diagramBulletsSlide("QUARKUS · TWO-PHASE MIGRATION", "Phase A lift, Phase B idiomatic", "r-two-phase-migration",
  [
    { lead: "Phase A — lift", text: "run the Spring code on Quarkus via Spring-compatibility extensions; get the equivalence suite green fast." },
    { lead: "Phase B — idiomatic", text: "refactor to JAX-RS, Panache, and CDI; measure the result." },
    { lead: "Phase B — native", text: "a GraalVM Mandrel build is the measured payoff of the build-time model." },
    { lead: "Measured, not asserted", text: "JVM ~1.5 s / ~316 MB; native ~0.05 s / ~73 MB on the same Postgres and profile." },
  ],
  "Every service came across the same way, in two phases, and the figure carries the real numbers. Phase A lifts the existing Spring code onto Quarkus using the Quarkiverse Spring-compatibility extensions — spring-web, spring-di, spring-data-jpa — so the service runs on the new runtime quickly and the behavior-equivalence suite goes green before anything is rewritten. That de-risks the move: the cutover is proven first, the refactor second. Phase B then rewrites to idiomatic Quarkus — JAX-RS endpoints, Panache persistence, CDI — and measures the before and after. The headline numbers are the payoff of the build-time model from the orientation: on the same Postgres and prod profile, JVM startup around a second and a half and roughly three hundred megabytes of RSS, versus a native image at about fifty milliseconds and seventy-odd megabytes.");

codeSlide("QUARKUS · PERSISTENCE", "Persistence with Panache + Flyway", "java · Panache",
  [
    "// a repository method — intent, not JPA boilerplate",
    "List<OrderOutboxEvent> findUnpublished(int batchSize);",
    "",
    "// the scheduled relay reads the outbox and publishes, in one tx",
    "@Scheduled(every = \"${order.outbox.relay.poll-interval:2s}\")",
    "@Transactional",
    "void publishUnpublishedEvents() {",
    "    List<OrderOutboxEvent> batch = outboxRepository.findUnpublished(BATCH_SIZE);",
    "    for (OrderOutboxEvent event : batch) publishOne(event);",
    "}",
    "",
    "# schema is owned and versioned by the service, applied at boot:",
    "quarkus.flyway.schemas=order_service",
    "quarkus.flyway.migrate-at-start=true",
  ],
  "Owned data — Panache repository + Flyway, from order-service",
  "Persistence shows two Quarkus habits at once. Panache keeps the data layer about intent: a repository exposes a method like findUnpublished and the framework supplies the query, so there is no DAO boilerplate to read past. Here that repository backs the transactional outbox relay — a scheduled method runs every two seconds, reads a batch of unpublished events, and publishes them, all inside one @Transactional method so the read-and-mark is atomic. The @Scheduled extension provides the timer; no cron wiring. And each service owns its schema: Flyway migrations live with the service, target its own schema, and run at startup, so the database shape is versioned code rather than a shared migration nobody owns. Database-per-service is enforced by this combination, not by convention.");

codeSlide("QUARKUS · MESSAGING OUT", "Publishing — the outbox relay to Kafka", "java · SmallRye Reactive Messaging",
  [
    "# application.properties — a channel is configuration",
    "mp.messaging.outgoing.order-placed.connector=smallrye-kafka",
    "mp.messaging.outgoing.order-placed.topic=order.placed",
    "",
    "// the relay emits onto the channel; the connector handles Kafka",
    "@Channel(\"order-placed\")",
    "Emitter<String> emitter;",
    "",
    "void publishOne(OrderOutboxEvent event) {",
    "    emitter.send(event.getPayload());   // at-least-once; retried on failure",
    "}",
  ],
  "Producing events — SmallRye Reactive Messaging",
  "SmallRye Reactive Messaging is the Quarkus implementation of MicroProfile Reactive Messaging, and it makes Kafka a matter of configuration plus a tiny bit of code. A channel is declared in application.properties — this one maps the logical name order-placed to the smallrye-kafka connector and the order.placed topic. The code then just emits onto the channel; the connector owns broker connections, serialization, and retries. Pairing this with the outbox from the previous slide is the key move: the business row and the outbox row commit together in Postgres, and the relay publishes afterward, so there is never a dual-write where the database and Kafka can disagree. The service never touches a KafkaProducer directly — the connector is the chassis doing the messaging plumbing.");

codeSlide("QUARKUS · MESSAGING IN", "Consuming — the idempotent @Incoming handler", "java · SmallRye Reactive Messaging",
  [
    "# the incoming channel, declared in configuration",
    "mp.messaging.incoming.order-placed.connector=smallrye-kafka",
    "mp.messaging.incoming.order-placed.topic=order.placed",
    "",
    "@Incoming(\"order-placed\")",
    "public void consume(OrderPlacedEvent event) {",
    "    service.recordOrderPlaced(event);   // idempotent upsert, keyed by orderId",
    "    LOG.infof(\"consumed order.placed for order %d\", event.orderId());",
    "}",
  ],
  "Consuming events — the notification service",
  "The consuming side mirrors the producing side: an incoming channel declared in configuration, and a method annotated @Incoming that receives already-deserialized events. The method body stays small — it hands the event to a service that performs an idempotent upsert keyed by the order id. That idempotency is not optional: Kafka delivery is at-least-once, so a handler will occasionally see the same event twice, and the correctness of the whole event-driven system depends on the handler tolerating that. The pattern across every consumer in the repository is the same — do the work in terms of a business key so a replay is a no-op. Between this slide and the last, the entire asynchronous backbone is two configuration blocks and two small methods; the connector is everything else.");

codeSlide("QUARKUS · gRPC", "A gRPC service, reactive", "java · quarkus-grpc",
  [
    "@GrpcService",
    "public class InventoryGrpcServiceImpl implements InventoryService {",
    "",
    "    @Override",
    "    public Uni<StockReply> checkStock(CheckStockRequest request) {",
    "        var sku = request.getStockKeepingUnit();",
    "        // look up the item, compute whether demand can be satisfied",
    "        return Uni.createFrom().item(toStockReply(sku, item, canSatisfy));",
    "    }",
    "    // reserve(...) / release(...) / getStock(...) — same shape",
    "}",
  ],
  "The gRPC seam — inventory-service's server",
  "Inventory exposes a gRPC surface because the order-to-inventory seam is the one place in the system with a different wire contract — the proto vocabulary of stock_keeping_unit and on_hand_qty versus the internal model. quarkus-grpc generates the service base and the stubs from inventory.proto at build time; the implementation annotated @GrpcService returns Uni, Quarkus's reactive type, so the call is non-blocking on the Vert.x event loop. This is also where the real anti-corruption layer lives, as the Camel ACL slide noted: the client translates internal to proto on the way out and the server translates proto to internal on the way in. The client side carries a configurable deadline via @ConfigProperty, which is the one place fault tolerance was warranted.");

codeSlide("QUARKUS · GRAPHQL", "Aggregation with SmallRye GraphQL", "java · SmallRye GraphQL",
  [
    "@GraphQLApi",
    "public class GatewayApi {",
    "",
    "    @Query(\"order\")",
    "    public OrderView order(@Id @Name(\"id\") String id) {",
    "        return OrderView.from(orderRestClient.getById(id));      // REST",
    "    }",
    "",
    "    // a nested field, resolved lazily — over gRPC:",
    "    public StockView stock(@Source OrderItemView item) {",
    "        var reply = inventoryGrpc.getStock(/* sku */);           // gRPC",
    "        return new StockView(reply.getStockKeepingUnit(),",
    "                             reply.getOnHandQty(), reply.getAvailable());",
    "    }",
    "}",
  ],
  "The aggregation gateway — graphql-gateway",
  "The gateway is a data-less aggregation layer: it owns no database and exists only to compose the services into one query surface. SmallRye GraphQL turns that into declarative code. A top-level @Query resolves an order over REST from the order service. The interesting part is @Source: a method taking an OrderItemView resolves that type's nested stock field on demand, and it does so over gRPC to inventory — so a single GraphQL query fans out across both REST and gRPC, and the nested resolvers only run if the client actually asks for those fields. The client makes one call instead of five, and each field is served by whichever service and protocol is appropriate. No schema file is hand-written; SmallRye derives it from the annotated types.");

leadSlide("QUARKUS · SECURITY", "Identity at the edge service",
  [
    { lead: "review-service carries its own identity", text: "it no longer depends on the monolith's global security wiring." },
    { lead: "Built-in authentication", text: "HTTP basic auth over an embedded identity store, enabled in configuration." },
    { lead: "Role-gated writes", text: "a write requires the CUSTOMER role; an unauthenticated write is rejected." },
    { lead: "Proven by the suite", text: "the equivalence collection asserts the 401-then-201 pair, so the policy is tested, not assumed." },
  ],
  "Security is shown on the one service that needed its own, review-service, because it is the clearest small example. When it was extracted it stopped relying on the monolith's global security and took on its own: HTTP basic authentication over an embedded identity store, turned on with a handful of configuration keys — quarkus.http.auth.basic and a demo user with the CUSTOMER role. Writes are role-gated, so an unauthenticated write is rejected with a 401 while an authenticated one succeeds with a 201. The reason to trust that claim is that the behavior-equivalence suite asserts exactly that 401-then-201 pair, so the security policy is covered by the same gate as everything else. Basic auth with an embedded store is a demonstrator choice; the point is that identity moved into the service and is enforced and tested there.");

diagramBulletsSlide("QUARKUS · THE DEV LOOP", "The inner dev loop", "r-quarkus-dev-loop",
  [
    { lead: "Dev Services", text: "mvn quarkus:dev starts Postgres and Kafka as throwaway containers automatically — no infra to run by hand." },
    { lead: "Live reload", text: "save a file and the next request recompiles in place, sub-second, no restart." },
    { lead: "Continuous testing", text: "affected tests re-run on save; press 'r' to resume — the suite is always nearby." },
    { lead: "One command", text: "the whole loop is quarkus:dev; nothing else to start or wire." },
  ],
  "The inner dev loop is where the build-time model pays off for the developer, not just the runtime. One command, mvn quarkus:dev, starts everything. Dev Services notices the datasource and messaging extensions and stands up throwaway Postgres and Kafka containers automatically, so there is no local infrastructure to install or docker-compose to remember — and because the same Testcontainers machinery backs integration tests, dev and test share an environment. Live reload recompiles on the next request after you save, in a fraction of a second, with no restart, so the edit-run cycle feels interpreted. Continuous testing watches for changes and re-runs the affected tests automatically. The effect is that the equivalence suite and the services are always a keystroke away while you work, which is exactly what makes agent-driven change safe to iterate on.");

leadSlide("QUARKUS · NATIVE & AOT", "Native image and the JDK 25 AOT cache",
  [
    { lead: "Native image (Mandrel)", text: "./mvnw package -Dnative produces a standalone binary — ~0.05 s start, ~73 MB RSS." },
    { lead: "The build-time model makes it possible", text: "a closed-world, reflection-free runtime is what GraalVM needs to compile ahead of time." },
    { lead: "JVM AOT cache (JDK 25)", text: "where native is not wanted, the JDK 25 AOT cache still cuts JVM warm-up." },
    { lead: "Density in production", text: "fast start and small footprint mean more services per node and quicker scale-up." },
  ],
  "Native image is the headline payoff of everything in act two. Because Quarkus has already done the framework work at build time and the runtime does almost no reflection, GraalVM Mandrel can compile the service ahead of time into a standalone native binary — and the measured result on this project is roughly fifty-millisecond startup and about seventy megabytes of resident memory, an order of magnitude better than the JVM figures. That is not a free lunch: a native build takes longer and the closed-world assumption occasionally needs reflection hints, which the chapters document. Where a native image is not wanted, the JDK 25 ahead-of-time cache still trims JVM warm-up. Either way the operational consequence is density — more services per node and faster scale-up — which matters once six services become the unit of deployment.");

leadSlide("QUARKUS · SPRING-COMPAT", "Phase A: the Spring-compatibility bridge",
  [
    { lead: "Run Spring code on Quarkus", text: "Quarkiverse spring-web, spring-di, and spring-data-jpa extensions accept the familiar annotations." },
    { lead: "Prove the move first", text: "the service passes the equivalence suite on Quarkus before a line is rewritten." },
    { lead: "A bridge, not a destination", text: "compat gets you running fast; Phase B replaces it with idiomatic Quarkus." },
    { lead: "Risk, split in two", text: "the cutover and the refactor become separate, independently verifiable steps." },
  ],
  "Phase A is what made each extraction safe to start. The Quarkiverse Spring-compatibility extensions let a service keep its Spring annotations — @RestController, @Autowired, Spring Data repositories — while running on Quarkus, so the code moves to the new runtime with minimal change and the behavior-equivalence suite can confirm the move before any rewrite begins. That separation is the real value: the cutover and the idiomatic refactor become two independent steps, each provable on its own, rather than one big risky rewrite. The compatibility layer is explicitly a bridge, not a destination — Phase B, on the earlier two-phase slide, replaces it with JAX-RS, Panache, and CDI and measures the improvement. Starting on compat is how you get a green suite on day one.");

leadSlide("QUARKUS · REST", "Quarkus REST: endpoints, reactive-ready",
  [
    { lead: "quarkus-rest-jackson", text: "JAX-RS resources with Jackson binding — the services' public HTTP surface." },
    { lead: "Reactive core, imperative feel", text: "runs on the Vert.x event loop; blocking and non-blocking handlers both supported." },
    { lead: "Build-time routing", text: "endpoints are wired during augmentation, part of why startup is fast." },
    { lead: "REST client, same model", text: "the gateway's @RestClient interfaces call services with the same stack." },
  ],
  "The HTTP surface of every service is Quarkus REST — the current JAX-RS implementation, added as quarkus-rest-jackson for JSON binding. Two things stand out. It is reactive at its core, running on the Vert.x event loop, but it lets you write ordinary blocking handlers where that is simpler and reserves non-blocking return types like Uni for where they pay off — so the programming model stays approachable. And like everything else in Quarkus, the routing table is built at augmentation time rather than scanned at boot, which is part of the fast-startup story. The same stack powers the declarative REST client the GraphQL gateway uses to call services, so the inbound and outbound HTTP sides share one model.");

leadSlide("QUARKUS · ARC (CDI)", "ArC: dependency injection, resolved at build",
  [
    { lead: "Build-time CDI", text: "ArC resolves the bean graph during augmentation — not by reflection at startup." },
    { lead: "Dead code falls away", text: "unused beans are pruned, shrinking the image and the attack surface." },
    { lead: "Standard annotations", text: "@ApplicationScoped, @Inject, @Produces — the edge router and saga beans use them." },
    { lead: "Why it is fast and native-ready", text: "a resolved, closed bean graph is what the native build needs." },
  ],
  "ArC is Quarkus's CDI implementation and a concrete instance of the build-time idea. In a traditional container the bean graph is discovered and wired by reflection at startup; ArC does that resolution during augmentation and bakes the result in, so startup does almost no injection work. A useful side effect is pruning: beans that nothing references are removed from the image, which shrinks both the footprint and the attack surface. The programming model is standard CDI — the edge router and the saga beans are @ApplicationScoped, configuration is injected with the MicroProfile @ConfigProperty seen earlier, and @Produces makes the InMemorySagaService a managed bean. The reason this matters beyond tidiness is native image: a resolved, closed-world bean graph is exactly what ahead-of-time compilation requires.");

{
  const s = S();
  addContentTitle(s, "QUARKUS · MESSAGING", "The order service's channels at a glance");
  addStatusTable(s, [
    { code: "order-placed", name: "outgoing", purpose: "The outbox relay publishes order.placed to Kafka." },
    { code: "payment-captured", name: "incoming", purpose: "Saga listener advances the order on capture." },
    { code: "payment-declined", name: "incoming", purpose: "Saga listener fails the order on decline." },
    { code: "shipment-dispatched", name: "incoming", purpose: "Saga listener completes the order on dispatch." },
    { code: "shipment-failed", name: "incoming", purpose: "Saga listener compensates on shipment failure." },
  ], { colW: [3.10, 2.00, 7.09] });
  addNotes(s, "This is the order service's full messaging surface, declared entirely in application.properties as five channels — one outgoing and four incoming. The outgoing channel carries order.placed, published by the transactional-outbox relay. The four incoming channels are the order service's saga listeners reacting to the choreographed workflow: payment captured or declined, and shipment dispatched or failed. Reading this table top to bottom is reading the choreographed saga from the order service's point of view — it emits that an order was placed, then responds to what payment and shipping say happened. The point for this deck is that the entire asynchronous contract of a service is legible as configuration; the connector supplies the Kafka machinery and the @Incoming methods supply the reactions.");
}

leadSlide("QUARKUS · METRICS", "Metrics by dependency",
  [
    { lead: "Micrometer", text: "add the extension and JVM, HTTP, and datasource meters are published — no meter code." },
    { lead: "A scrape endpoint", text: "metrics are exposed for Prometheus-style collection into the LGTM stack." },
    { lead: "Custom meters when needed", text: "inject a MeterRegistry to count domain events — used sparingly, where it adds signal." },
    { lead: "Same opt-in model", text: "observability, like config and health, is a dependency, not a framework to build." },
  ],
  "Metrics round out the chassis and follow the same opt-in rule as config and health. Adding the Micrometer extension publishes a useful default set out of the box — JVM memory and threads, HTTP server request timings, datasource pool stats — with no meter code written, exposed on an endpoint the LGTM collector scrapes. Where a domain-specific metric helps, you inject a MeterRegistry and register a counter or timer, which the project does sparingly rather than instrumenting everything by reflex. The recurring theme across this act holds here too: observability is acquired by declaring a dependency, and the cost of a capability — its runtime weight and its surface — is visible as a line in the pom you can add or remove.");

leadSlide("QUARKUS · TESTING", "Three tiers, on Dev Services",
  [
    { lead: "Unit", text: "plain JUnit and Mockito over the domain logic — fast, no container." },
    { lead: "Integration", text: "@QuarkusTest with REST Assured against the running app on throwaway Postgres and Kafka." },
    { lead: "Behavior-equivalence", text: "the Newman collection asserts observable behavior across a seam — the merge gate." },
    { lead: "Dev Services everywhere", text: "the same throwaway infrastructure backs dev mode and the integration tier." },
  ],
  "Testing spans three tiers, and Dev Services is what makes the middle tier painless. Unit tests are ordinary JUnit and Mockito over the domain logic, fast and container-free. Integration tests use @QuarkusTest with REST Assured to drive the actually-running application, and because the datasource and messaging extensions are present, Dev Services stands up a throwaway Postgres and Kafka for them automatically — no compose file to run first, and the same machinery that backs quarkus:dev. On top sits the behavior-equivalence suite, the Newman collection that asserts observable behavior across a seam and serves as the merge gate for the whole migration. The three tiers answer different questions — logic, wiring, and behavior — and all three run in CI, which is what let agent-driven change proceed safely.");

// ===== SECTION C — TOGETHER ==================================================
divider("C", "Together", "Topology, telemetry, and capability by service",
  "Act three steps back to the whole. We annotate the final topology as Quarkus services with Camel glue, show how one trace crosses both runtimes through OpenTelemetry, and close the technical arc with a matrix of which service demonstrates which capability — so the deck doubles as a map of where to look in the repository.");

diagramBulletsSlide("TOGETHER · TOPOLOGY", "Quarkus services, Camel glue", "r09-final-topology",
  [
    { lead: "Six services + a gateway", text: "every box is a Quarkus app owning its schema in Postgres." },
    { lead: "Camel at the edge", text: "the content-based router is the one front door into the mesh." },
    { lead: "Camel inside shipping", text: "the Saga EIP orchestrator coordinates fulfilment." },
    { lead: "Kafka between services", text: "outbox relays and choreographed saga events flow over the broker." },
    { lead: "One runtime family", text: "services and routes are all Quarkus processes — uniform to build, start, and operate." },
  ],
  "This is the general 201's end-state topology, re-read through this deck's lens. Every application box is a Quarkus process that owns its schema in Postgres. The two Camel elements are visible in their roles: the content-based edge router is the single front door, and the Saga EIP orchestrator lives inside shipping. Kafka carries the asynchronous traffic — the outbox relays that publish each service's events and the choreographed payment saga. The operational payoff of the one-runtime-family choice is uniformity: services and routes build the same way, start in the same fraction of a second, expose the same health and metrics endpoints, and go native by the same command. There is no second technology to operate for the integration layer, which is a real reduction in operational surface.");

leadSlide("TOGETHER · TELEMETRY", "One trace across both runtimes",
  [
    { lead: "quarkus-opentelemetry", text: "add the extension and point three properties at the collector — no span code." },
    { lead: "Auto-instrumented", text: "JAX-RS endpoints, the REST client, and gRPC emit spans automatically." },
    { lead: "Camel participates", text: "routes propagate trace context, so an edge-to-service hop stays one trace." },
    { lead: "Exported to LGTM", text: "spans land in Tempo; the mesh adds its own Envoy spans on top (general 201 / mesh chapter)." },
    { lead: "Context is the app's job", text: "the mesh amplifies a trace; the instrumented services start and propagate it." },
  ],
  "Observability closes the loop and shows the two runtimes cooperating. Tracing is another extension-as-capability: add quarkus-opentelemetry, set three properties — the OTLP endpoint, the service name, and the sampler — and the JAX-RS server, the REST client, and gRPC are all auto-instrumented, with no manual span code anywhere in the services. Camel propagates the same trace context across a route, so a request that enters the edge router and fans out to services stays a single trace rather than fragmenting at the integration layer. The spans export to the LGTM stack and, on Kubernetes, the service mesh adds Envoy spans for every hop on top — covered in the general 201. The one thing the application still owns is starting and propagating context; the mesh amplifies a trace, it does not originate one.");

{
  const s = S();
  addContentTitle(s, "TOGETHER · CAPABILITY MAP", "Which service shows which capability");
  addStatusTable(s, [
    { code: "order", name: "Panache · Kafka · CQRS", purpose: "Outbox relay, @Incoming saga listeners, gRPC client; CQRS write/read split." },
    { code: "inventory", name: "quarkus-grpc server", purpose: "The reactive gRPC surface and the real ACL at the proto boundary." },
    { code: "payment", name: "Choreographed saga", purpose: "Event-driven reactions over Kafka; compensation by choreography." },
    { code: "shipping", name: "Camel Saga EIP", purpose: "The orchestrated saga route with a single compensation leg." },
    { code: "notification", name: "SmallRye + WebSockets", purpose: "Idempotent @Incoming consumer and a WebSockets.Next push surface." },
    { code: "review", name: "Security", purpose: "Its own basic-auth identity; role-gated writes proven by the suite." },
    { code: "gateway", name: "SmallRye GraphQL", purpose: "REST + gRPC fan-out behind one @GraphQLApi query surface." },
  ], { colW: [1.80, 3.10, 7.19] });
  addNotes(s, "This matrix is the deck's index into the repository: each service was chosen to demonstrate a specific capability, so you can jump straight to the one you care about. Order is the richest — Panache persistence, the Kafka outbox and saga listeners, a gRPC client, and the CQRS write-read split. Inventory is the gRPC server and the real anti-corruption layer. Payment is the choreographed saga; shipping is the orchestrated one, so the two sit side by side. Notification shows the idempotent consumer plus a WebSockets.Next push endpoint. Review carries its own security. The gateway is SmallRye GraphQL fanning out over REST and gRPC. If you take one thing from this deck, take this map — it tells you where every pattern lives.");
}

codeSlide("TOGETHER · TRACING", "OpenTelemetry: three properties, auto-instrumented", "properties · quarkus-opentelemetry",
  [
    "# add the quarkus-opentelemetry extension, then:",
    "quarkus.otel.exporter.otlp.traces.endpoint=http://lgtm.mea.svc.cluster.local:4317",
    "quarkus.otel.service.name=order-service",
    "quarkus.otel.traces.sampler=always_on   # demo sampler — not for production",
    "",
    "// no span code anywhere: JAX-RS, the REST client, and gRPC",
    "// are auto-instrumented; Camel propagates the same trace context",
  ],
  "Tracing across both runtimes — order, inventory, and the gateway",
  "Tracing is the clearest example of the opt-in chassis spanning both runtimes. Adding quarkus-opentelemetry and setting three properties — the OTLP endpoint, the service name, and the sampler — turns on distributed tracing with no span-creation code anywhere: the JAX-RS server, the REST client, and gRPC are all auto-instrumented. Camel propagates the same trace context across a route, so a request entering the edge router and fanning out stays a single trace rather than fragmenting at the integration layer. The sampler here is always_on, flagged in the comment as a demo setting — a production service would sample a fraction of traffic. On Kubernetes the service mesh adds Envoy spans for every hop on top of these, but the application still owns starting and propagating context; the mesh amplifies a trace, it does not originate one.");

{
  const s = S();
  addContentTitle(s, "TOGETHER · DEMOS", "The demos that drive the two runtimes");
  addStatusTable(s, [
    { code: "demo-equivalence", name: "The gate", purpose: "Runs the Newman suite through the Camel edge router against the live topology." },
    { code: "demo-cutover", name: "Edge router", purpose: "Flips a seam and proves the suite stays green across the content-based route." },
    { code: "demo-shipping-cutover", name: "Camel Saga EIP", purpose: "Exercises the orchestrated saga and its compensation leg." },
    { code: "demo-schema-registry", name: "Avro + Apicurio", purpose: "Contract-first event evolution against the registry." },
    { code: "demo-final-topology", name: "End state", purpose: "The six Quarkus services + gateway, with the monolith gone." },
  ], { colW: [3.30, 2.30, 6.59] });
  addNotes(s, "The repository ships runnable demos, and this table picks the ones that best exercise the two runtimes so a reader knows where to see the code move. demo-equivalence runs the behavior-equivalence suite straight through the Camel edge router against the live system — it is the gate in action. demo-cutover flips a seam and shows the suite staying green across the content-based route, the strangler mechanic live. demo-shipping-cutover drives the orchestrated Camel saga including its compensation path. demo-schema-registry shows contract-first Avro evolution against Apicurio. And demo-final-topology brings up the whole end state — six Quarkus services and the gateway, the monolith gone. Each is a shell script in the repo; the full matrix is in the general 201's appendix.");
}

// ===== APPENDIX ==============================================================
divider("D", "Appendix", "Reference — keep open in another window",
  "The appendix is reference material to keep alongside the repository: the Quarkus extensions in play, the Camel components and EIPs, the pinned stack and versions, and a short glossary. It is not meant to be read front to back.");

{
  const s = S();
  addContentTitle(s, "APPENDIX · QUARKUS", "Quarkus extensions reference");
  addStatusTable(s, [
    { code: "rest-jackson", name: "REST + JSON", purpose: "JAX-RS endpoints with Jackson binding (Quarkus REST)." },
    { code: "hibernate-orm-panache", name: "Persistence", purpose: "Active-record / repository Panache over Hibernate ORM." },
    { code: "flyway", name: "Migrations", purpose: "Per-service schema, versioned and migrated at startup." },
    { code: "messaging-kafka", name: "Eventing", purpose: "SmallRye Reactive Messaging over Kafka (@Incoming / @Channel)." },
    { code: "grpc", name: "gRPC", purpose: "Code-generated reactive gRPC server and clients." },
    { code: "smallrye-graphql", name: "GraphQL", purpose: "Schema-from-code aggregation API on the gateway." },
    { code: "scheduler", name: "Scheduling", purpose: "@Scheduled timers — drives the outbox relays." },
    { code: "smallrye-health", name: "Health", purpose: "Liveness / readiness endpoints for Kubernetes probes." },
    { code: "opentelemetry", name: "Tracing", purpose: "Auto-instrumented OTLP traces to the LGTM collector." },
  ], { colW: [2.70, 2.30, 7.09], rowH: 0.40 });
  addNotes(s, "A one-page map of the extensions the services actually declare, so a reader can recognize them in a pom.xml. Each line is a capability from act two: REST and JSON binding, Panache persistence, Flyway migrations, SmallRye Kafka messaging, gRPC, SmallRye GraphQL, the scheduler behind the outbox relays, health for the probes, and OpenTelemetry for traces. The through-line is the same one the chassis slide made: these are dependencies, not frameworks the team maintains. Adding a line to the pom turns a capability on; removing it turns the capability — and its startup cost and attack surface — off. That is the opt-in chassis in its most literal form.");
}

{
  const s = S();
  addContentTitle(s, "APPENDIX · CAMEL", "Camel components & EIPs reference");
  addStatusTable(s, [
    { code: "platform-http", name: "Component", purpose: "The edge router's single HTTP consumer." },
    { code: "http", name: "Component", purpose: "Transparent producer to each service (bridgeEndpoint)." },
    { code: "direct / bean", name: "Component", purpose: "In-process saga steps delegating to CDI beans." },
    { code: "saga", name: "Component", purpose: "The Saga EIP SPI (InMemorySagaService here)." },
    { code: "CBR", name: "EIP", purpose: "Content-Based Router — the edge dispatch by path." },
    { code: "Saga", name: "EIP", purpose: "Orchestrated fulfilment with one compensation leg." },
    { code: "Outbox", name: "EIP", purpose: "Transactional outbox + scheduled relay to Kafka." },
    { code: "Idempotent", name: "EIP", purpose: "At-least-once-safe consumers keyed by business id." },
  ], { colW: [2.40, 1.90, 7.79], rowH: 0.42 });
  addNotes(s, "The Camel half of the reference, split into components and patterns. The components are the concrete endpoints the two routes use: platform-http and http at the edge, direct and bean inside the saga, and the saga coordinator itself. The patterns are the Enterprise Integration Patterns those routes realize: the Content-Based Router at the edge, the orchestrated Saga in shipping, the Transactional Outbox in every producer, and the Idempotent Consumer in every Kafka handler. Pairing the two columns is useful when reading the code — a pattern tells you the intent, a component tells you the endpoint that implements it. For a production deployment the one component to revisit is the saga service, where InMemorySagaService would give way to a durable coordinator.");
}

{
  const s = S();
  addContentTitle(s, "APPENDIX · STACK", "Stack & versions");
  addStatusTable(s, [
    { code: "JDK 25", name: "Language runtime", purpose: "SDKMAN; the JVM path plus the JDK 25 AOT cache." },
    { code: "Quarkus 3.40.1", name: "Service runtime", purpose: "Platform BOM for every service and gateway." },
    { code: "Camel 4.22", name: "Integration", purpose: "camel-quarkus; the edge router and the saga." },
    { code: "Spring Boot 4.1", name: "The 'before'", purpose: "The reference monolith; Phase A compat bridge." },
    { code: "Kafka 4.3 (KRaft)", name: "Event backbone", purpose: "Outbox relays and choreographed saga events." },
    { code: "PostgreSQL 18", name: "Storage", purpose: "One schema per service; the outbox tables." },
    { code: "Apicurio 3.3.3", name: "Schema registry", purpose: "Avro, contract-first event evolution." },
    { code: "Mandrel", name: "Native builder", purpose: "GraalVM-based native-image compilation." },
  ], { colW: [2.70, 2.30, 7.09], rowH: 0.42 });
  addNotes(s, "The pinned versions, for reproducibility — these drift, so re-check them against the repository before quoting them. JDK 25 is the baseline, with both the JVM and the AOT-cache path in play. Quarkus 3.40.1 is the platform BOM shared by every service, which is what keeps the extension versions aligned. Camel 4.22 arrives through camel-quarkus. Spring Boot 4.1 runs the frozen monolith shell (it was built on 3.5) and 3.5 was the Phase A compatibility bridge. Kafka in KRaft mode is the event backbone; PostgreSQL holds one schema per service plus the outbox tables; Apicurio provides the Avro registry for contract-first events; and Mandrel is the native-image builder. The general 201 and the book carry the full matrix with the per-service specifics.");
}

leadSlide("APPENDIX · GLOSSARY", "Glossary",
  [
    { lead: "Build-time augmentation", text: "Quarkus doing scan/wire/proxy once at build, not per boot." },
    { lead: "Extension", text: "a Quarkus dependency that turns a capability on with little or no code." },
    { lead: "Dev Services", text: "throwaway Postgres/Kafka containers started automatically in dev and test." },
    { lead: "Panache", text: "Quarkus's active-record / repository layer over Hibernate ORM." },
    { lead: "EIP", text: "Enterprise Integration Pattern — e.g. Content-Based Router, Saga." },
    { lead: "Saga / compensation", text: "a multi-step workflow with an undo action per failure, orchestrated or choreographed." },
    { lead: "Transactional outbox", text: "commit the event with the business row, relay to Kafka afterward — no dual write." },
    { lead: "Native image", text: "an ahead-of-time compiled binary; fast start, small footprint." },
  ],
  "A short glossary for the terms this deck leans on, aimed at someone who knows microservices but is newer to Quarkus and Camel. Build-time augmentation and extension are the two that unlock the rest — they are why startup is fast and why capabilities are opt-in dependencies. Dev Services and Panache are the day-to-day developer terms. EIP, saga, and transactional outbox are the integration vocabulary that the Camel act used. And native image is the production payoff. None of these are project-specific; they are the standard vocabulary of the two runtimes, collected here so the code slides read cleanly on a second pass.");

// ===== CLOSING ===============================================================
{
  const s = S();
  addContentTitle(s, "CLOSING", "Where to go deeper");
  leadBullets(s, [
    { lead: "The repository", text: "every snippet here is a real file — the routes, the extensions, the messaging wiring, runnable on Docker Engine and minikube." },
    { lead: "The book", text: "the full 32-chapter tutorial, each chapter with a runnable example and a verification footer." },
    { lead: "The other decks", text: "the 101 for the ideas, the general 201 for the whole migration; this deck is the framework lens on both." },
    { lead: "Start where your pain is", text: "pick the service in the capability map that matches your problem, and read its module." },
  ]);
  addNotes(s, "To close: this deck was the framework lens, and the best next step is the code it points at. Everything shown is a real file in the repository — the edge router, the saga route, the extension lists, the messaging configuration — and all of it runs on Docker Engine for development and minikube for the Kubernetes chapters. The book carries the full narrative, chapter by chapter, each with a runnable example and a verification footer. The other two decks cover the parts this one assumed: the 101 for the ideas and the general 201 for the whole migration. And the most practical entry point is the capability map a few slides back — find the service that matches the problem in front of you and read that module first.");
}

pres.writeFile({ fileName: OUT })
  .then(p => console.log("WROTE", p, "slides:", pageNum))
  .catch(e => { console.error(e); process.exit(1); });
