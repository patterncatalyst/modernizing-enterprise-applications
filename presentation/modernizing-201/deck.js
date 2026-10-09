// deck.js — "Modernizing Enterprise Applications" 201 deep-dive deck.
// Red Hat house style, 16:9. Build: NODE_PATH=./node_modules node deck.js
"use strict";

const H = require("./deck-helpers.js");
const {
  COLOR, FONT, W, PNG, ASSETS,
  newDeck, addFooter, addContentTitle, addBullets, addTwoColBullets,
  addStatusTable, addCaption, addCodeSlide, addSectionDivider, addNotes,
} = H;

const OUT = "Modernizing-201-r1.0.pptx";
const REV = "r1.0";

const pres = newDeck();
pres.title = "Modernizing Enterprise Applications — 201 implementation deep-dive";
let pageNum = 0;

function S() { const s = pres.addSlide(); pageNum += 1; addFooter(s, pageNum); return s; }
function divider(code, title, subtitle, notes) {
  const s = pres.addSlide(); pageNum += 1; addSectionDivider(s, code, title, subtitle); addNotes(s, notes);
}

// ---- local helper: bold-lead bullets (bold subject run + normal text run) ----
// Mirrors the datamesh deck's addBullets {lead, text, lvl} rich-text pattern.
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

// A content slide: title + bold-lead bullets.
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

// ===== COVER =================================================================
{
  const s = pres.addSlide(); pageNum += 1;
  s.background = { color: COLOR.white };
  try { s.addImage({ path: `${ASSETS}/cover-panel.png`, x: 0, y: 0, w: W, h: 7.5 }); } catch (e) {}
  s.addText("MODERNIZING ENTERPRISE APPLICATIONS · 201", { x: 6.00, y: 1.98, w: 6.90, h: 0.34,
    fontFace: FONT.title, fontSize: 14, bold: true, color: COLOR.red, charSpacing: 5, align: "left", valign: "middle" });
  s.addText([{ text: "The running", options: { breakLine: true } },
             { text: "system,", options: { breakLine: true } },
             { text: "in detail" }], {
    x: 5.95, y: 2.42, w: 6.95, h: 2.70, fontFace: FONT.title, fontSize: 50, bold: true, color: COLOR.ink, align: "left", valign: "top" });
  s.addText("An implementation deep-dive: the six extractions, code where the code is the lesson, the demos behind the system, and a reference appendix.",
    { x: 6.00, y: 5.15, w: 6.80, h: 1.10, fontFace: FONT.body, fontSize: 17, italic: true, color: COLOR.caption, align: "left", valign: "top" });
  s.addText(REV, { x: 11.85, y: 6.10, w: 0.95, h: 0.30, fontFace: FONT.mono, fontSize: 11, color: COLOR.caption, align: "right", valign: "middle" });
  try { s.addImage({ path: `${ASSETS}/logo-candidate-2.png`, x: 11.10, y: 6.78, w: 1.55, h: 0.37 }); } catch (e) {}
  addNotes(s, "This is the 201 deep-dive. The 101 deck makes the conceptual case — why modernize, the ADLC, the strangler idea, the pattern map; this deck is the running system and how it is built: the six extractions in detail, real code where the code carries the lesson, the demos behind the system, and a reference appendix. No prior Quarkus or Camel experience is assumed — each capability is introduced where it comes up. Everything here traces to a running reference system: six services, a strangler proxy, a behaviour-equivalence suite, a Kubernetes deploy tree, and a supply-chain gate, and every figure and number traces to running code. It states what works, what is opt-in, and what is deferred.");
}

// ===== AGENDA ================================================================
{
  const s = S();
  addContentTitle(s, "201 · DEEP DIVE", "The implementation, one section per part — plus a reference appendix");
  addTwoColBullets(s,
    ["01 · Why modernize — and when not to", "02 · The ADLC — the method that drives it", "03 · The reference monolith", "04 · Finding the seams", "05 · The strangler fig in practice", "06 · Data across the seam"],
    ["07 · Coordinating across services", "08 · Communication, contracts & the chassis", "09 · Operating & delivering", "10 · The pattern language, revisited", { text: "Appendix · demo matrix, examples, contracts,", muted: true }, { text: "CI gates, stack, glossary, figures", muted: true }]);
  addNotes(s, "The deck follows the book, one section per part, and goes deep: diagram-forward, with code slides where the code is the lesson and a slide per key demo. The first two sections are the case for change and the method; the middle sections are the migration itself, seam by seam, with the real Quarkus and Camel code each one produced; the last two operate the result and review which patterns earned their place. The appendix is reference material to keep open in another window — the demo matrix, the runnable-examples catalog, the context contracts, the CI gates, the stack and versions, a glossary, and background figures. Any section stands alone as a shorter talk.");
}

// ===== 01 · WHY MODERNIZE ====================================================
divider("01", "Why Modernize", "The case for change — and the discipline to leave most of it alone.",
  "Modernization is not a goal in itself; it costs money and risk. The reason to do it is that change has become slow or dangerous in a system that still delivers value. This section frames when modernization pays and when it does not.");

leadSlide("WHY MODERNIZE · THE CASE", "Modernize because change got expensive",
  [{ lead: "The monolith is often where the value is", text: "a working system encodes years of business rules; the problem is when every change is slow and coupled to everything else." },
   { lead: "The target is independent deployability", text: "not a service count — ship the parts that change at different rates without redeploying the whole." },
   { lead: "Sam Newman's playbook drives this book", text: "incremental decomposition over a big-bang rewrite — Monolith to Microservices, one seam at a time." },
   { lead: "A rewrite bets the company on a flag day", text: "it discards encoded rules and replaces a known system with an unproven one in a single cutover. We never take that bet." }],
  "The opening argument. Modernization earns its cost only when the rate of change the business needs exceeds what the current architecture absorbs safely. The target to keep in view is independent deployability, not a service count — that distinction returns at the very end, in over-decomposition. The governing voice through the whole book is Sam Newman: decompose incrementally, keep the system serving traffic the entire time, and never stake the business on one rewrite cutover. Everything that follows is a way to make that incremental path safe.");

leadSlide("WHY MODERNIZE · RESTRAINT", "And when not to modernize at all",
  [{ lead: "Stable and low-value", text: "a system that rarely changes and carries little strategic weight is cheapest left exactly as it is." },
   { lead: "Encapsulate before you extract", text: "put an API in front of a component you are not ready to touch; buy the option without paying for the move." },
   { lead: "No seam, no split", text: "if a context has no real internal boundary, forcing one in produces coupling, not independence." },
   { lead: "Modernization is a means", text: "the test is always whether a specific change gets safer or cheaper — never whether the result looks more modern." }],
  "Restraint is half the discipline. The common failure is treating modernization as a virtue and applying it everywhere, which spends the budget on systems that gained nothing. A stable, low-value system is cheapest left alone. A component you are not ready to extract can still be wrapped behind an API, which buys the option to move later without paying now. And forcing a split where the domain has no real seam manufactures coupling rather than removing it. The question for every candidate is narrow: does a change the business needs get safer or cheaper? If not, leave it.");

diagramBulletsSlide("WHY MODERNIZE · STRATEGY", "Pick a strategy by value and risk",
  "r01-modernization-2x2",
  [{ lead: "Retain / encapsulate", text: "low value, low risk — leave it running, wrap it behind an API." },
   { lead: "Rehost / replatform", text: "high value, low risk — the quick wins: containerize, move onto Kubernetes." },
   { lead: "Refactor / re-architect", text: "high value, high change — the strangler fig, done incrementally. This book lives here." },
   { lead: "Retire / replace", text: "low value, high risk — sunset it, or rebuild only if the value justifies it." }],
  "The six Rs are not a single choice for the whole system; you score each capability by business value against the change and risk of modernizing it. Low-value, low-risk components you encapsulate and leave alone. High-value, low-risk ones are quick replatform wins. The strangler fig lives in the top-right quadrant: high value and high change, the parts worth refactoring carefully and incrementally. That quadrant is where the migration happens. The grid is a triage tool — run it over a real portfolio and most components sort themselves into leave-alone or quick-win, leaving a handful that deserve the strangler treatment.");

leadSlide("WHY MODERNIZE · THE TARGET", "What a modern shape buys you in 2026",
  [{ lead: "Independent deployability", text: "ship one context without redeploying the rest — the structural win the whole migration drives toward." },
   { lead: "Data ownership", text: "each service owns its schema; no shared-database coupling underneath the service boundaries." },
   { lead: "A chassis you opt into", text: "config, health, metrics, tracing, and native builds arrive as dependencies, not a framework you maintain." },
   { lead: "A Kubernetes-native baseline", text: "the mesh does identity, OpenTelemetry does signals, the platform does discovery — the roles the Netflix-OSS stack once filled." }],
  "What modernization delivers, concretely. Independent deployability and data ownership are the structural wins; the chassis and the Kubernetes-native baseline are what make running many services tractable in 2026. The operational answers have moved: a decade ago teams built service discovery, client-side load balancing, and circuit breaking into the application with the Netflix-OSS stack — Eureka, Ribbon, Hystrix, Zuul. Today the platform and the mesh provide identity, traffic management, and discovery, and OpenTelemetry provides the signals. You adopt those capabilities rather than build them, which is the chassis idea that section eight develops.");

// ===== 02 · THE ADLC ========================================================
divider("02", "The ADLC", "The AI Development Lifecycle — the method that makes agent-driven change safe.",
  "This section is the one the original deck never had. The migration was driven by AI agents, and what makes that safe rather than reckless is a specific discipline built around an automated behavioural gate.");

leadSlide("THE ADLC · FROM SDLC", "From SDLC to ADLC: the human moves up a level",
  [{ lead: "The SDLC assumes a human authors each line", text: "the ADLC assumes an agent generates the change and a human frames, gates, and reconciles it." },
   { lead: "That only works with an automated answer", text: "to 'is this change correct?' — otherwise every merge is a leap of faith." },
   { lead: "So the method is built around a gate", text: "not around trust in the model's output." },
   { lead: "The human's job shifts", text: "from typing code to framing the seam, setting the acceptance bar, and checking what ran against what was claimed." }],
  "The conceptual shift. In the classic lifecycle the human is the author; in the ADLC the human becomes the framer and the gatekeeper while the agent is the author. That reassignment is only responsible if correctness can be checked without a person reading every diff. Hold that thought, because the gate is the next two slides and it is the single most important idea in this section. The economic driver underneath it: when generating a change becomes cheap, the cost and the risk both move to verification, so that is where the method puts its weight.");

diagramBulletsSlide("THE ADLC · THE LOOP", "Five phases, every increment",
  "r02-adlc-loop",
  [{ lead: "Frame", text: "name the seam and the smell it cures." },
   { lead: "Plan", text: "sequence the work, record the decisions, set the acceptance bar." },
   { lead: "Generate", text: "the agent writes the code, using skills and MCP tools." },
   { lead: "Verify", text: "the behaviour-equivalence suite must be green, in CI. This is the gate." },
   { lead: "Reconcile", text: "record what actually ran against what was claimed; the next increment builds on it." }],
  "The loop runs once per increment — here, once per seam. Frame names the seam and the smell it removes. Plan sequences the work and sets the acceptance bar. Generate is where the agent writes the code. Verify is the gate: the behaviour-equivalence suite must be green in CI. Reconcile records what actually ran versus what was only claimed, so the gap stays visible rather than drifting into documentation nobody trusts. The red marker in the figure is the whole point: a red suite blocks the merge. The method does not ask you to trust the agent; it asks you to trust a gate that is objective and runs on every push.");

leadSlide("THE ADLC · THE TOOLS", "Agents, skills, and MCP tools in the loop",
  [{ lead: "Agents", text: "do the generation — the code, the tests, the manifests." },
   { lead: "Skills", text: "package a repeatable procedure so quality does not depend on who ran it: a migration recipe, a diagram generator, a deck builder." },
   { lead: "MCP tools", text: "give the agent ground truth — query the running Quarkus app, validate a Camel route, look up a current API — instead of guessing." },
   { lead: "The recipe repeats", text: "reusable skills are why all six extractions followed the same disciplined steps rather than being improvised." }],
  "The machinery under the loop. Agents generate; skills turn a good procedure into something repeatable so the outcome does not depend on who ran it; MCP tools keep the agent anchored to live, version-matched ground truth rather than training-data recall. This deck was built with one of those skills. The reason all six extractions look alike — the same two-phase migration, the same cutover ritual, the same verification footer — is that the procedure lived in a skill, not in one engineer's head. When generation is cheap, repeatable procedure and automated verification are what keep quality from varying run to run.");

leadSlide("THE ADLC · THE SAFETY NET", "The behaviour-equivalence suite is the net",
  [{ lead: "It asserts observable behaviour", text: "a Newman/Postman collection pins the contract a user sees, not the internals — so a service can be rewritten underneath it." },
   { lead: "Green across every cutover", text: "each extraction kept it green through the proxy, in both flag positions, before the old module was removed." },
   { lead: "Run on every push, in CI", text: "which turns agent-generated change into something as reviewable as any other change — the gate is objective." },
   { lead: "Remove it and the method collapses", text: "without the suite, every later step is a leap of faith. With it, the whole migration is safe." }],
  "The net itself. The suite asserts behaviour — the observable contract — not implementation, which is exactly why a service can be rebuilt underneath it and the suite still proves nothing broke for the caller. It ran across every cutover, in both flag positions, and in CI on every push. The claim the whole book rests on: agent-driven change is safe to the degree that its verification is automated and behavioural. The investment that makes the ADLC work is not a better model; it is a behavioural gate good enough to make a red result mean 'do not merge'.");

// ===== 03 · THE MONOLITH ====================================================
divider("03", "The Reference Monolith", "Six bounded contexts, one shared schema, and planted coupling smells.",
  "You cannot teach a cure without a disease. The book ships a fresh Spring Boot monolith with real coupling smells, each planted to demonstrate the pattern that later removes it.");

diagramBulletsSlide("THE MONOLITH · SHAPE", "A monolith built to be taken apart",
  "r03-monolith-contexts",
  [{ lead: "Six bounded contexts", text: "order, inventory, payment, shipping, notification, review — in one Spring Boot process." },
   { lead: "One shared schema", text: "every context reads and writes the same PostgreSQL schema, with foreign keys across boundaries." },
   { lead: "In-process coupling", text: "contexts call each other as method calls — fast, and impossible to deploy apart." },
   { lead: "It runs", text: "a working system with a test suite, serving the behaviour the equivalence suite pins down." }],
  "The starting system: a Spring Boot monolith in a realistic e-commerce domain with six bounded contexts, all sharing one PostgreSQL schema, with foreign keys reaching across context boundaries and in-process calls between them. The smells are planted to demonstrate their cures, but the system still runs — it has a three-tier test suite, and it serves the behaviour the equivalence suite later pins down. Everything the migration does is visible as the gradual removal of exactly these smells. Starting from a running, tested monolith is what lets every later step be measured against a fixed baseline.");

leadSlide("THE MONOLITH · THE DOMAIN", "The checkout, end to end",
  [{ lead: "order", text: "accepts the checkout and owns the order lifecycle." },
   { lead: "inventory", text: "reserves stock for the order's line items." },
   { lead: "payment", text: "captures payment, or declines it." },
   { lead: "shipping", text: "dispatches once payment is captured." },
   { lead: "notification + review", text: "notify the customer; accept reviews after the fact." },
   { lead: "Today it is one transaction", text: "the whole chain runs in-process under a single commit — the coupling the migration unwinds." }],
  "One domain walkthrough to anchor the sections that follow, because every pattern lands on this checkout. A customer places an order; stock is reserved; payment is captured or declined; shipping dispatches on a successful capture; the customer is notified, and may review afterward. In the monolith this entire chain runs in-process, inside one database transaction, which is why a failure anywhere rolls back cleanly — and why nothing can be deployed on its own. As the contexts become services, that single transaction becomes a saga across six owners, and the coordination section shows both ways to hold it together.");

{
  const s = S();
  addContentTitle(s, "THE MONOLITH · SMELLS", "Each smell maps to the pattern that removes it");
  addStatusTable(s, [
    { code: "Shared schema", name: "DB per service", purpose: "Six contexts, one schema, cross-context FKs — resolved by owned data (Part 6)." },
    { code: "Dual write", name: "Transactional outbox", purpose: "Write DB then publish loses events on crash — resolved by the outbox (Part 6)." },
    { code: "Sync chain", name: "Sagas", purpose: "In-process checkout chain — resolved by choreographed & orchestrated sagas (Part 7)." },
    { code: "In-proc calls", name: "gRPC / REST seam", purpose: "Direct method calls across contexts — resolved by explicit seams (Part 5/7)." },
    { code: "ACID everywhere", name: "ACID -> ACD", purpose: "One cross-context transaction — relaxed to per-service consistency (Part 6)." },
  ], { colW: [2.60, 2.90, 6.59] });
  addNotes(s, "The smells are planted, and each is tagged to the pattern that removes it, which keeps the book's cause and effect legible. A shared schema is resolved by database-per-service; dual-write by the transactional outbox; the synchronous in-process checkout chain by sagas; cross-context method calls by explicit gRPC or REST seams; and the single cross-context ACID transaction by relaxing to per-service consistency, ACID to ACD. Read this table as the migration backlog: every row is a later chapter, and the order roughly follows the coupling ladder. Nothing in the migration appears without a smell on this table that motivates it.");
}

{
  const s = S();
  addCodeSlide(s, "THE MONOLITH · THE GATE", "The equivalence suite pins observable behaviour", "newman · postman",
    [
      "// the contract a user sees — asserted on the monolith and on every service",
      "pm.test('checkout returns 202 + a pending order', () => {",
      "  pm.response.to.have.status(202);",
      "  const o = pm.response.json();",
      "  pm.expect(o.status).to.eql('PENDING');",
      "});",
      "// bounded-wait: the terminal status arrives within budget (async-safe)",
      "pm.test('order reaches CONFIRMED', () => pm.expect(terminal).to.eql('CONFIRMED'));",
    ],
    "The same assertions run through the strangler proxy before and after each cutover — green is the merge bar.");
  addNotes(s, "A concrete look at the net. These assertions describe what a caller observes — a 202 and a pending order, then a terminal status within a time budget — and say nothing about which service produced it. That is why the same collection validates the monolith and, later, each extracted service through the proxy. The bounded-wait assertion is the small adaptation that lets a synchronous contract survive the move to asynchronous, event-driven processing: instead of asserting an immediate result, it waits a bounded time for the terminal status, which keeps the check meaningful without making it flaky. Run this on every push and a behavioural regression fails the build.");
}

// ===== 04 · FINDING THE SEAMS ===============================================
divider("04", "Finding the Seams", "Event storming, bounded contexts, and the coupling ladder.",
  "Before you cut, you find where to cut. The seams come from the domain, not from a target service count, and that is what keeps the resulting services aligned to real boundaries.");

diagramBulletsSlide("FINDING THE SEAMS · STORMING", "Cluster the events — the clusters are the seams",
  "r04-event-storm-seams",
  [{ lead: "Event storming", text: "put the domain events on a wall: OrderPlaced, StockReserved, PaymentCaptured, ShipmentDispatched, and the rest." },
   { lead: "Cluster by aggregate", text: "the group of events that belong to one aggregate is a bounded context." },
   { lead: "The boundaries are the seams", text: "where one cluster ends and the next begins is where a service boundary can go." },
   { lead: "Discovered, not invented", text: "six contexts came out of the domain; the service count followed from them." }],
  "Event storming is the technique for finding seams. Get the domain events on a wall — OrderPlaced, StockReserved, PaymentCaptured, ShipmentDispatched, CustomerNotified, ReviewSubmitted — and cluster them by the aggregate that owns them. The clusters are the bounded contexts, and the gaps between clusters are the candidate service boundaries. The order of operations matters: the six contexts came out of the domain first, and the decision to build six services followed from them. A team that starts by picking a number and then carves the domain to fit produces boundaries that cut through real transactions, which is the failure the next section's coupling ladder is designed to avoid.");

leadSlide("FINDING THE SEAMS · COUPLING", "Rank the seams by how hard they will be",
  [{ lead: "Not all seams are equal", text: "a read-mostly context with few inbound dependencies is a gentle first cut; a core aggregate others depend on is the hardest." },
   { lead: "The coupling ladder", text: "orders the extractions so each one is only as hard as it needs to be, and earlier cuts de-risk later ones." },
   { lead: "review first", text: "low coupling, a true walking skeleton that proves the whole loop on the easiest seam." },
   { lead: "order last", text: "the core aggregate, extracted only once everything it depends on already stands alone." }],
  "Finding seams is half the job; sequencing them is the other half. The coupling ladder ranks contexts by inbound dependency and rate of change, so you extract the gentle ones first and the load the system turns on last. Review is the walking skeleton — read-mostly, few dependencies — which makes it the right place to prove the entire Frame-to-Reconcile loop before the stakes rise. Order is the central aggregate, so it comes last, once inventory, payment, and shipping already stand on their own. The sequence is a design decision, and it also fixes the order in which the book introduces each data and coordination pattern.");

// ===== 05 · STRANGLER FIG ===================================================
divider("05", "The Strangler Fig in Practice", "A proxy in front, one seam out at a time, always reversible.",
  "The strangler fig is the core move. A proxy sits in front of the monolith; each seam routes to an extracted service behind a flag; and the equivalence suite stays green across every flip.");

diagramBulletsSlide("STRANGLER · THE MECHANISM", "Route each path to old or new, by flag",
  "r05-strangler-proxy",
  [{ lead: "A Camel proxy in front", text: "a content-based router sits ahead of everything; the client's API never changes." },
   { lead: "A flag per context", text: "off routes the path to the monolith module; on routes it to the extracted Quarkus service." },
   { lead: "Flip on green, revert on drift", text: "cut over only when the suite is green with the flag on; flip back instantly if anything moves." },
   { lead: "Six extractions, one order", text: "review, notification, inventory, payment, shipping, order — each following the same recipe." }],
  "The mechanism. A Camel proxy with a content-based router sits in front of everything, and the client's API never changes through the whole migration. For each context there is a flag: off, the path reaches the monolith module; on, it reaches the extracted Quarkus service. You flip a flag only once the suite is green with it on, and you can flip it back the instant anything drifts, which is what makes each cutover reversible rather than a commitment. The ladder along the bottom is the order the book follows. The proxy is also where content-based routing and the anti-corruption layer live, the subject of the next slide.");

{
  const s = S();
  addCodeSlide(s, "STRANGLER · THE CODE", "The edge router is one Camel choice()", "java · camel",
    [
      "from(\"platform-http:/api?matchOnUriPrefix=true\")",
      "  // the client's API never changes — method, path, query, body pass through",
      "  .choice()",
      "    .when(simple(\"${header.CamelHttpPath} startsWith '/api/reviews'\"))",
      "      .to(reviewServiceBaseUrl + \"?bridgeEndpoint=true\")",
      "    .when(simple(\"${header.CamelHttpPath} startsWith '/api/inventory'\"))",
      "      .to(inventoryServiceBaseUrl + \"?bridgeEndpoint=true\")   // gRPC-backed service",
      "    .when(simple(\"${header.CamelHttpPath} startsWith '/api/orders'\"))",
      "      .to(orderServiceBaseUrl + \"?bridgeEndpoint=true\")",
      "    .otherwise()",
      "      .to(monolithBaseUrl + \"?bridgeEndpoint=true\")   // anything not yet extracted",
    ],
    "examples/01-strangler-proxy · StranglerProxyRoute — content-based routing by path prefix; base URLs are @ConfigProperty.");
  addNotes(s, "The strangler proxy is not a framework; it is one Camel route. A content-based router matches each request by its path prefix and forwards it, unchanged, to the extracted service for that context — method, path, query string, and body all pass straight through, so the client's API never changes through the whole migration. During the migration each branch was flag-gated: with the flag off the path fell through to the monolith, with it on it reached the Quarkus service. The base URLs are injected with @ConfigProperty, which is the chassis idea arriving early. The otherwise branch is the strangler in one line: anything not yet extracted still goes to the monolith, so the fig grows over the host one branch at a time.");
}

leadSlide("STRANGLER · THE MOVES", "Four moves, and the anti-corruption layer",
  [{ lead: "Intercept", text: "put the proxy in front and prove the suite is green through it before changing anything." },
   { lead: "Extract", text: "stand up the service — lift it onto Quarkus, then make it idiomatic." },
   { lead: "Redirect", text: "flip the flag; the suite gates the cutover; reversibility holds until decommission." },
   { lead: "Decommission", text: "delete the monolith module once the service is sole owner." },
   { lead: "The anti-corruption layer", text: "translates old model to new — and when the seam is right, it has almost nothing to translate.", lvl: 1 }],
  "The four moves, repeated six times: intercept, extract, redirect, decommission. The anti-corruption layer deserves attention. Its job is to keep the monolith's model from leaking into the new service, so the service is built around its own clean model rather than the legacy one. The useful signal is how much the layer has to do: when you have found a true seam, the layer translates almost nothing, because the data crossing the boundary is the data the domain already exchanged there. A layer doing heavy translation is a sign the boundary is in the wrong place — which ties straight back to finding seams in the domain rather than imposing them.");

leadSlide("STRANGLER · EXTRACTION 1", "Review: the walking skeleton, two-phase migration",
  [{ lead: "Phase A — lift", text: "move the code onto Quarkus with Spring-compatibility extensions, changing as little as possible; get the suite green fast." },
   { lead: "Phase B — idiomatic", text: "refactor to native Quarkus (JAX-RS, Panache, CDI) and measure the before and after." },
   { lead: "The payoff is concrete", text: "native startup in tens of milliseconds, on a fraction of the memory — the chassis value, measured." },
   { lead: "Simplest seam first", text: "review proves the whole loop end to end before the harder extractions raise the bar." }],
  "The first extraction proves the method on the easiest seam. The two-phase strategy is what de-risks it. Phase A lifts the code onto Quarkus using Spring-compatibility extensions, so the equivalence suite goes green quickly on a bridge that behaves like the original. Phase B then refactors to idiomatic Quarkus — JAX-RS, Panache, CDI — and measures the result: native images starting in tens of milliseconds on a fraction of the memory a JVM monolith needs. Review is the simplest slice by design, because the goal of the first pass is to run the entire Frame-to-Reconcile loop once, on low stakes, before inventory and order raise the difficulty.");

// ===== 06 · DATA ACROSS THE SEAM ============================================
divider("06", "Data Across the Seam", "From a shared schema to owned data — without losing an event.",
  "Splitting behaviour is the straightforward half; splitting data is where migrations get hard. This section is the sequence of patterns that lets each service own its data safely.");

leadSlide("DATA · OWNERSHIP", "Database per service: the point of the exercise",
  [{ lead: "Each service owns its schema", text: "cross-context foreign keys become value references — an id you hold, not a join you own." },
   { lead: "This is what deployability needs", text: "you cannot deploy a service on its own while it shares a table with another." },
   { lead: "The hard part is the handoff", text: "moving ownership of live data with no downtime and no lost writes." },
   { lead: "Two tools do it safely", text: "the transactional outbox for new events, and change data capture to backfill — both next." }],
  "Database-per-service is the destination the whole migration drives toward, because shared data is the coupling that defeats independent deployment. The mechanical change is turning cross-context foreign keys into value references: the order service keeps an inventory item's id, not a join into inventory's tables. The hard part is the handoff — transferring ownership of data that is being written right now, with no downtime and no lost writes. The outbox and change data capture are the two tools that make that handoff safe, and the next two slides take them in turn.");

diagramBulletsSlide("DATA · THE OUTBOX", "One transaction, then relay",
  "r06-outbox-pipeline",
  [{ lead: "You cannot write DB and Kafka atomically", text: "they are two systems; a crash between the two steps loses the event." },
   { lead: "So write both in one local transaction", text: "the business row and an outbox row commit together, atomically." },
   { lead: "A relay publishes afterward", text: "a scheduled poller reads the outbox and sends to Kafka — at-least-once, retriable." },
   { lead: "Consumers are idempotent", text: "at-least-once means duplicates; handlers tolerate them by design." }],
  "The transactional outbox solves the most common distributed-data bug. You cannot atomically write your database and publish to Kafka, because they are two systems with no shared transaction. So you do not try: in one local transaction you write the business row and an outbox row, and a separate relay polls the outbox and publishes afterward. The anti-pattern the figure calls out is dual-write — write the database, then publish — which silently loses the event if the process dies in between. The outbox makes the write atomic and the publish a retriable, at-least-once follow-up, which is why every consumer downstream is written to tolerate duplicates.");

{
  const s = S();
  addCodeSlide(s, "DATA · THE CODE", "Relay the outbox; consume idempotently", "java · quarkus",
    [
      "// producer side — a scheduled relay drains the outbox table to Kafka",
      "@Scheduled(every = \"${payment.outbox.relay.poll-interval:2s}\")",
      "@Transactional",
      "void publishUnpublishedEvents() {",
      "    for (PaymentOutboxEvent e : outbox.findUnpublished(BATCH_SIZE)) {",
      "        emitterFor(e.getEventType()).send(e.getPayload());  // at-least-once",
      "        e.markPublished();",
      "    }",
      "}",
      "",
      "// consumer side — SmallRye Reactive Messaging; handler tolerates duplicates",
      "@Incoming(\"order-placed\")",
      "public void consume(OrderPlacedEvent event) { service.record(event); }",
    ],
    "examples/05-payment-service · PaymentOutboxRelay + examples/03-notification-service · OrderPlacedConsumer.");
  addNotes(s, "The two halves of event-driven data, in real code. On the producer side, a Quarkus @Scheduled method polls the outbox table every couple of seconds inside a transaction, sends each unpublished row to Kafka through a SmallRye emitter, and marks it published — the Spring @Scheduled-plus-KafkaTemplate pattern becomes Quarkus @Scheduled plus an emitter, with no dual write. On the consumer side, a SmallRye Reactive Messaging @Incoming method receives the event; because delivery is at-least-once, the handler is written to tolerate a duplicate rather than assume exactly-once. This is the whole shape of the system's data plane: write locally, relay afterward, consume idempotently. The notification consumer leans on a single-writer guarantee across replicas so exactly one instance records any given event.");
}

leadSlide("DATA · CDC AS A BRIDGE", "Change data capture: a bridge you take down",
  [{ lead: "Debezium tails the write-ahead log", text: "during the inventory cutover it backfills the new service's owned schema from the monolith's WAL." },
   { lead: "It runs only during the window", text: "once the monolith stops writing inventory, a script retires the connector, slot, and publication." },
   { lead: "A transition tool, not an architecture", text: "a replication pipeline left running with no owner is itself a coupling." },
   { lead: "Temporary by design", text: "infrastructure added for the cutover and removed on completion — the same discipline throughout." }],
  "Change data capture keeps the new service's data current while both old and new are live. For the inventory extraction, Debezium tails the monolith's write-ahead log and backfills the owned schema, so the new service has correct data before it takes any traffic. The discipline that matters is that it is temporary: the moment the monolith stops writing inventory, a script retires the connector, the replication slot, and the publication. Change data capture here is a bridge you walk across and then dismantle. Leaving it running forever would be a new standing coupling between two databases — a cost with no remaining benefit.");

leadSlide("DATA · ES, CQRS, ACID→ACD", "Use CQRS where it fits; set the rest aside",
  [{ lead: "CQRS — used", text: "the order service splits a write model from a rebuildable read model, because the read and write shapes diverge enough to justify it." },
   { lead: "Full event sourcing — deferred", text: "the concept is covered and set aside; this system needs neither temporal queries nor an event log as its system of record." },
   { lead: "ACID → ACD", text: "the single cross-context transaction relaxes to per-service consistency, with sagas restoring end-to-end correctness." },
   { lead: "Fit over fashion", text: "a pattern is used when it solves a pain the system has — not because it is advanced." }],
  "Three data patterns, three decisions. CQRS is used where it fits: the order service's read and write shapes diverge enough to justify a separate, rebuildable read model projected from its events. Full event sourcing is covered and then set aside, because this system needs neither temporal queries nor an event log as its system of record, and that machinery — versioned events, snapshots, rebuild tooling — is a real cost with no matching need here. ACID-to-ACD names the consistency change: the single cross-context transaction becomes per-service consistency, and sagas, the next section, restore correctness across the whole. Choosing by fit rather than fashion is itself a design skill.");

// ===== 07 · COORDINATING ====================================================
divider("07", "Coordinating Across Services", "Sagas — two styles, same checkout — and failure as a first-class concern.",
  "Once services own their data, a workflow that was one transaction now spans several services. Sagas coordinate it; both styles appear on the same checkout, and failure is treated as something you design for.");

diagramBulletsSlide("COORDINATING · SAGAS", "Choreography and orchestration, side by side",
  "r07-choreo-vs-orchestr",
  [{ lead: "Choreographed (payment)", text: "services react to each other's events with no central coordinator — decoupled, resilient, but diffuse." },
   { lead: "Orchestrated (shipping)", text: "a Camel Saga EIP route sequences the steps and their compensations — legible, central, in the critical path." },
   { lead: "Compensation, not rollback", text: "across services there is no distributed rollback; a compensating action undoes a committed step." },
   { lead: "You will use both", text: "the choice is per workflow, for stated reasons — not one correct style." }],
  "Both saga styles, on the same checkout, because the choice between them is a real design judgement. On the left, choreography: services react to each other's events with no central coordinator, which is loosely coupled and resilient but diffuse, since no single place describes the whole workflow. On the right, orchestration with the Camel Saga EIP: a route explicitly sequences the steps and their compensations, which is legible and owned, but puts the coordinator in the critical path. The payment extraction is choreographed; the shipping extraction is orchestrated. The lesson is not to pick a winner — it is that a real system uses both, in different places, for reasons you can state.");

{
  const s = S();
  addCodeSlide(s, "COORDINATING · THE CODE", "The orchestrated saga is a Camel route", "java · camel",
    [
      "from(\"direct:ship-start\")",
      "  .saga()",
      "    .propagation(SagaPropagation.REQUIRES_NEW)",
      "    .completionMode(SagaCompletionMode.AUTO)",
      "    .timeout(SAGA_TIMEOUT_SECONDS, TimeUnit.SECONDS)",
      "    .compensation(\"direct:ship-compensate\")   // runs once, on any step's failure",
      "  .to(\"direct:ship-enrich\")         // step 1",
      "  .to(\"direct:ship-dispatch\")       // step 2 — persists before the fail point",
      "  .to(\"direct:ship-book-carrier\")   // step 3 — may throw",
      "  .to(\"direct:ship-emit-dispatched\")// step 4 — only on success",
      "  .end();",
    ],
    "examples/06-shipping-service · ShipmentSagaRoute — the coordinator calls the compensation leg exactly once on any failure.");
  addNotes(s, "Orchestration, as actual code. The shipping saga is a Camel route with a saga scope: the coordinator sequences four steps and, if any one of them throws, invokes the compensation leg exactly once to undo what already committed. The propagation is REQUIRES_NEW so each step owns its transaction; the completion mode is automatic; a timeout bounds the whole saga so a stuck step cannot hang forever. Compensation is the hard part the previous slide named — there is no distributed rollback, so ship-compensate is a semantic inverse that releases what dispatch reserved. Read against the choreographed payment saga, this is the trade-off made concrete: one place to read the whole flow, at the cost of putting the coordinator in the path. The in-memory saga coordinator is right for this demonstrator; a durable LRA implementation is the production step, named in the close.");
}

leadSlide("COORDINATING · THE CHOICE", "When to choreograph, when to orchestrate",
  [{ lead: "Choreograph", text: "when steps are naturally reactive and decoupling matters more than a visible end-to-end flow — payment reacting to order.placed." },
   { lead: "Orchestrate", text: "when the flow is complex, has real compensation logic, and someone must see and own it — shipping's dispatch-or-compensate." },
   { lead: "Compensation is the hard part", text: "a committed step is undone by a semantic inverse, not by a rollback that does not exist across services." },
   { lead: "The saga buys back correctness", text: "the end-to-end guarantee the ACID-to-ACD relaxation gave up." }],
  "The decision, stated as a rule. Choreography suits naturally reactive steps where decoupling matters more than a visible flow. Orchestration suits complex flows with real compensation logic that one place should own and observe. The concept both share, and the one most teams underestimate, is compensation: across services there is no rollback, only a compensating action that semantically undoes a step that already committed — refund the capture, release the reservation. The saga is what buys back the end-to-end correctness that the ACID-to-ACD relaxation gave up, which is why the saga section follows the data section directly.");

leadSlide("COORDINATING · RESILIENCE", "Design for the failures you have",
  [{ lead: "Timeouts and deadlines", text: "on every cross-service call; the gRPC inventory seam carries explicit deadlines." },
   { lead: "Idempotency", text: "on every consumer — at-least-once delivery guarantees duplicates eventually." },
   { lead: "Retries, with care", text: "safe only where the operation is idempotent; otherwise a retry compounds the failure." },
   { lead: "Circuit breaker — deferred", text: "with no cascading-failure load to tune against, adding one would be guesswork rather than protection." }],
  "Resilience applied as judgement rather than a checklist. Timeouts and deadlines go on every cross-service call, so a slow dependency fails fast instead of holding a thread; the gRPC inventory seam carries explicit deadlines. Idempotency goes on every Kafka consumer, because at-least-once delivery guarantees a duplicate will arrive eventually. Retries go only where the operation is safe to repeat. The circuit breaker is deferred, and the reason is stated: with no real cascading-failure load to tune its thresholds against, adding one is a configuration guess wearing the shape of rigor. Design for the failure modes the system has — the same restraint as the data patterns.");

// ===== 08 · COMMUNICATION, CONTRACTS, CHASSIS ===============================
divider("08", "Communication, Contracts & the Chassis", "The platform you opt into, and the contracts that keep services compatible.",
  "With six services talking, two things matter: the cross-cutting foundation each service runs on, and the contracts that keep their conversations from breaking silently.");

diagramBulletsSlide("CHASSIS · THE PLATFORM", "The chassis is a dependency, not a framework",
  "r08-chassis",
  [{ lead: "config", text: "@ConfigProperty — typed configuration, no boilerplate loader." },
   { lead: "health + metrics", text: "smallrye-health and Micrometer — probes Kubernetes reads, metrics the collector scrapes." },
   { lead: "tracing", text: "the OpenTelemetry extension plus three properties — spans with no business-logic change." },
   { lead: "native + dev services", text: "a GraalVM image from a build profile; zero-config backing services in dev." }],
  "The microservice chassis is the cross-cutting foundation every service needs — configuration, health checks, metrics, logging, tracing, graceful shutdown. When the pattern was first named, this was something a platform team built and maintained. With Quarkus and MicroProfile it has become something you opt into: configuration is @ConfigProperty; health is one extension that Kubernetes probes read; tracing is the OpenTelemetry extension plus three properties; native images and zero-config dev services arrive the same way. The service in the middle of the figure is business logic and little else. That is the modern chassis: the foundation is a set of dependencies you declare, not a framework you own.");

leadSlide("CHASSIS · OPT-IN", "Capability arrives when you declare it",
  [{ lead: "Three services, distributed tracing", text: "added by one extension and three properties — no change to business logic." },
   { lead: "Same for config, health, metrics", text: "declare the dependency, get the capability, wire nothing by hand." },
   { lead: "Native compilation", text: "sub-50ms startup from a build profile, not a rewrite." },
   { lead: "Effort moves to the domain", text: "you stop building the chassis and spend the time on the business." }],
  "The opt-in model, made concrete. In the observability work, three services gained distributed tracing by adding the OpenTelemetry extension and pointing three properties at a collector — with no change to their business logic. That is the pattern for the whole chassis: the capability becomes the service's capability the moment the dependency is declared. Native compilation is a build profile that yields sub-50-millisecond startup, not a porting project. The payoff is where engineering time goes: instead of building and maintaining a framework for config, health, metrics, and tracing, the team spends that time on the domain, which is the only part a competitor cannot also download.");

{
  const s = S();
  addCodeSlide(s, "CHASSIS · THE CODE", "Config, profiles, and tracing — all declarative", "properties · java",
    [
      "# application.properties — typed config, per-profile, no loader to write",
      "quarkus.application.name=order-service",
      "quarkus.http.port=8087",
      "%dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/monolith?currentSchema=order_service",
      "",
      "# distributed tracing: one extension + three properties, no code change",
      "quarkus.otel.exporter.otlp.traces.endpoint=http://lgtm.mea.svc.cluster.local:4317",
      "quarkus.otel.service.name=order-service",
      "quarkus.otel.traces.sampler=always_on",
      "",
      "// injected the same way, anywhere a bean needs it:",
      "@ConfigProperty(name = \"strangler.inventory.base-url\") String inventoryBaseUrl;",
    ],
    "examples/07-order-service · application.properties + @ConfigProperty — health, metrics and native builds arrive the same way.");
  addNotes(s, "The chassis, as configuration rather than code. Typed config is @ConfigProperty and per-profile keys — %dev and %prod select datasource settings with no loader to write. Distributed tracing is the clearest case: adding the OpenTelemetry extension and these three properties gave three services full tracing with no change to their business logic, which is exactly how the observability work was done. Health probes, Micrometer metrics, and a native build profile arrive the same way — declare the dependency, get the capability. None of this is framework code the team maintains; it is a platform the service opts into, which is why the service classes stay almost entirely business logic.");
}

leadSlide("CHASSIS · CAPABILITY TOUR", "One chassis, many Quarkus strengths on show",
  [{ lead: "Panache", text: "active-record persistence across the services — the entity is the repository, little boilerplate." },
   { lead: "Reactive Messaging", text: "SmallRye @Incoming / Emitter over Kafka — the outbox relays and saga consumers." },
   { lead: "gRPC", text: "the inventory seam is a typed, low-latency contract — @GrpcClient and a generated stub." },
   { lead: "SmallRye GraphQL", text: "the gateway's @GraphQLApi federates the services into one query surface." },
   { lead: "Camel on Quarkus", text: "the strangler proxy and the shipping saga are Camel routes, running on the Quarkus engine." },
   { lead: "Dev Services + native", text: "zero-config backing services in dev; a GraalVM image with sub-50ms startup from a build profile." }],
  "A capability tour, because the 'after' is not a 1:1 port — each service was chosen to show a Quarkus strength in anger. Panache makes persistence an active-record entity with little boilerplate. SmallRye Reactive Messaging is the Kafka plane behind the outbox relays and saga consumers. The inventory seam is gRPC — a typed, low-latency contract with a generated stub and a @GrpcClient. The gateway is SmallRye GraphQL, federating the services. The strangler proxy and the shipping saga are Camel on Quarkus, the same Camel you know running on the Quarkus engine. And across all of them, Dev Services give zero-config Postgres and Kafka in development, while a native build profile yields sub-50-millisecond startup. The chassis is what makes adopting each of these a dependency rather than a project.");

leadSlide("CONTRACTS · KEEPING SERVICES COMPATIBLE", "Contracts, the registry, and the shapes of talk",
  [{ lead: "REST, gRPC, GraphQL", text: "REST across most seams; gRPC where a typed low-latency call fits, like the inventory seam; GraphQL to aggregate reads at the edge." },
   { lead: "The GraphQL gateway", text: "federates the six services into one composed query surface, so a client makes one call instead of five." },
   { lead: "Events get a schema", text: "Avro plus the Apicurio registry make event evolution a reviewed contract, not a runtime surprise." },
   { lead: "Shipped as a demonstrator", text: "the pattern shown cleanly on one topic rather than retrofitted across every topic at once." }],
  "How the services talk, and how those conversations stay compatible. REST is the default; gRPC is used where a typed, low-latency contract fits, like the inventory seam; and a GraphQL aggregation gateway stitches the services into one query surface so a client makes one call rather than five. For events, the schema registry — Avro with Apicurio — turns event evolution into a reviewed contract, so a producer cannot change a payload shape in a way that breaks consumers at runtime without the registry rejecting it. We shipped the registry as a demonstrator on one topic, which shows the pattern cleanly; retrofitting it across every topic would be a migration of its own.");

// ===== 09 · OPERATING & DELIVERING ==========================================
divider("09", "Operating & Delivering", "Deploy, mesh, observe, and gate — including the supply chain.",
  "A migration you cannot operate is not finished. This section is the end-state topology on Kubernetes, the mesh and observability on it, the delivery pipeline, and a supply-chain gate that measures the whole modernization.");

diagramBulletsSlide("OPERATING · THE END STATE", "Six services + a gateway, the monolith gone",
  "r09-final-topology",
  [{ lead: "The monolith is decommissioned", text: "frozen in the repo as a reference, serving no traffic." },
   { lead: "The proxy is now the edge router", text: "it has shed every flag and routes permanently by path." },
   { lead: "Each service owns its schema", text: "six services plus the GraphQL gateway, each with its own data in PostgreSQL." },
   { lead: "Kafka carries the between", text: "outbox relays and saga events move between services over the broker." }],
  "The end state. The monolith is decommissioned — kept in the repository as a reference, but serving no traffic. The strangler proxy has shed all its flags and become the permanent edge router. Six services plus the GraphQL gateway each own their schema in PostgreSQL, and Kafka carries the outbox relays and saga events between them. This is what a completed strangler fig looks like: the fig has grown over the host, and the host is gone. Everything else in this section operates this topology — the mesh wraps these pods, the observability stack watches them, and the delivery pipeline is how a change reaches them.");

leadSlide("OPERATING · DEPLOY", "Deployment: declarative, layered, additive",
  [{ lead: "Kustomize", text: "a base of plain Kubernetes manifests with a minikube overlay on top — no templating engine, no managed cloud." },
   { lead: "Config and secrets externalized", text: "health probes are the readiness the platform keys on for rollout and restart." },
   { lead: "Added additively", text: "a deploy tree that never disturbed the running services." },
   { lead: "Headless is not done", text: "rendering and dry-run passed; the live apply then surfaced six bugs a dry-run could not catch." }],
  "Deployment is declarative and layered: a kustomize base of plain Kubernetes manifests with a minikube overlay, no Helm templating and no managed cloud on the primary path. The deploy tree was added additively, the same discipline as every other capability, and it never disturbed the running services. One result becomes a theme: the manifests first passed headless checks (rendered with kustomize, validated with a client-side dry-run), and then the live apply surfaced six real bugs the dry-run had no way to catch, from an image that needed a config change to a probe that was too aggressive. Verified headless and verified running are different claims.");

leadSlide("OPERATING · MESH & OBSERVABILITY", "Istio for identity, OpenTelemetry + LGTM for signals",
  [{ lead: "Istio", text: "gives every pod mutual TLS and request telemetry with no application code — the sidecar does it." },
   { lead: "Strict mTLS, after proof", text: "flipped from permissive to strict only once traffic was confirmed encrypted — verified on the cluster." },
   { lead: "OpenTelemetry + LGTM", text: "Loki, Grafana, Tempo, Mimir collect the signals; one real trace spans five hops across services." },
   { lead: "Deferred, and marked", text: "node-level log shipping into Loki and Kafka saga-hop tracing are not wired up yet." }],
  "Operability's two layers, both exercised on the minikube cluster. Istio's sidecar gives every meshed pod mutual TLS and request-level telemetry with no application code; mTLS was moved from permissive to strict only after confirming traffic was already encrypted. OpenTelemetry feeding the LGTM stack — Loki, Grafana, Tempo, Mimir — collects the signals, and the chapter captures one real request tracing across five hops. Two gaps are marked rather than papered over: node-level log shipping into Loki is not wired up, and the Kafka saga hops are not traced end to end yet. The subtle point: the mesh produces a span per hop, but it only amplifies a trace the application already started, so context propagation stays the application's job.");

leadSlide("DELIVERING · CI/CD", "Gates on every push; progressive delivery by flag",
  [{ lead: "CI is behaviour gates", text: "seven jobs run the equivalence and contract suites per seam on every push; a separate job builds and publishes the site." },
   { lead: "Progressive delivery", text: "the strangler's flag-gated cutover — reversible, gated on a green suite, retired once every seam was extracted." },
   { lead: "Named, not built", text: "a GitOps operator (Argo/Flux), an image-registry push, and Istio weighted canary are the next steps, not yet in the repo." },
   { lead: "No speculative infrastructure", text: "the same rule the whole book holds — build the machinery when the load calls for it." }],
  "Delivery, stated precisely. The CI that exists is a set of behaviour gates: seven jobs running the equivalence and contract suites per seam on every push, plus the job that builds and publishes the documentation site. Progressive delivery in this project was the strangler's flag-gated cutover — reversible, gated on a green suite, and retired once every seam was extracted. Three pieces are named but not built: a GitOps operator reconciling the cluster to git, an image-registry push, and Istio weighted canary for traffic-level rollout. They are the next steps, not a pipeline this repo pretends to have. The rule is to build operational machinery when a real load calls for it.");

diagramBulletsSlide("DELIVERING · SUPPLY CHAIN", "The gate that measures the modernization",
  "r09-supply-chain-gate",
  [{ lead: "syft → grype → policy", text: "generate a CycloneDX SBOM, scan it against the vulnerability database, fail the build on a High-or-worse finding." },
   { lead: "Policy as code", text: "the threshold lives in a committed file; the same steps run locally and in CI." },
   { lead: "monolith — fails", text: "4 Critical and 6 High, across spring-webmvc, tomcat, jackson, and the postgresql driver." },
   { lead: "order-service — passes", text: "0 Critical, 0 High, against the same database the same day." }],
  "The supply-chain gate, and the most direct result in the book. Three steps: syft generates a CycloneDX software bill of materials, grype scans it against the current vulnerability database, and a committed policy file fails the build on a High-or-worse finding — policy as code, the same steps on a laptop and in CI. Run it over the before and the after on the same day, against the same database: the Spring Boot monolith's fat jar fails on four Critical and six High findings, across spring-webmvc, tomcat, jackson, and the postgresql driver; the extracted Quarkus order-service passes at zero and zero. The scan is a moving target as new CVEs are disclosed, which is exactly why the gate runs on every push rather than once.");

{
  const s = S();
  addCodeSlide(s, "DELIVERING · THE CODE", "The supply-chain gate is three tools and a file", "yaml · bash",
    [
      "# policy.yaml — the threshold is committed, reviewed, versioned",
      "fail-on: High          # fail on any High or Critical finding",
      "",
      "# demo.sh — the same three steps run locally and in CI",
      "syft \"$artifact\" -o cyclonedx-json=sbom.json          # 1. bill of materials",
      "grype sbom:sbom.json -o json > findings.json          # 2. scan vs CVE DB",
      "crit=$(jq '[.matches[]|select(.severity==\"Critical\")]|length' findings.json)",
      "high=$(jq '[.matches[]|select(.severity==\"High\")]|length' findings.json)",
      "[ $((crit+high)) -gt 0 ] && exit 1   # 3. policy -> non-zero exit fails the build",
    ],
    "examples/10-supply-chain · policy.yaml + demo.sh — a non-zero exit is exactly what fails a CI job.");
  addNotes(s, "The gate, as code, because it is not exotic. The policy lives in a committed file — fail-on High — so the security rule is reviewed and versioned like anything else. The script is three steps: syft generates a CycloneDX bill of materials, grype scans that SBOM against the current vulnerability database, and a jq tally applies the threshold, exiting non-zero when a High-or-worse finding exists. That non-zero exit is the entire integration with CI; a job step that runs this and fails is the gate. Scanning the SBOM rather than the artifact again matters: the bill of materials is generated once and becomes the shared contract an auditor, the scanner, and a human all reason about. Run the before and after through it and you get the four-Critical monolith and the clean service from the previous slide.");
}

{
  const s = S();
  addCodeSlide(s, "DELIVERING · DRIVE IT", "The demos run the whole system", "bash",
    [
      "# prove the current topology still behaves, end to end",
      "./demos/demo-equivalence.sh        # Newman suite, green through the proxy",
      "",
      "# flip one seam old->new and prove equivalence holds across the flip",
      "./demos/demo-cutover.sh            # reversible; the suite gates the cutover",
      "./demos/demo-payment-cutover.sh    # per-seam: the choreographed saga",
      "./demos/demo-shipping-cutover.sh   # per-seam: the orchestrated saga",
      "",
      "# the end state: all six extracted, the monolith decommissioned",
      "./demos/demo-final-topology.sh",
    ],
    "demos/ — nine scripts; the full matrix is in the appendix. Each one is a runnable proof you can re-run.");
  addNotes(s, "The system is meant to be driven, not just described, and the demos are how. demo-equivalence runs the behaviour-equivalence suite against the current topology through the proxy — the same gate CI runs, on your machine. The cutover demos flip a seam from monolith to service and show the suite staying green across the flip, reversibly; the per-seam scripts each exercise one extraction, including the choreographed payment saga and the orchestrated shipping saga. demo-final-topology brings up the end state with all six services extracted and the monolith decommissioned. The full nine-script matrix, with what each one proves and which infrastructure tier it needs, is in the appendix. The point of shipping demos rather than screenshots is that every claim in this deck is one command away from being re-checked.");
}

// ===== 10 · REFLECTION ======================================================
divider("10", "The Pattern Language, Revisited", "Which patterns we used — and where to stop.",
  "The close. With the system built, we re-walk the catalog and give each pattern a verdict, name the anti-pattern that most often wrecks these migrations, and scope the remaining work.");

diagramBulletsSlide("REFLECTION · THE SCORECARD", "A verdict per pattern, against the finished system",
  "r10-pattern-scorecard",
  [{ lead: "Used", text: "strangler, bounded contexts, database-per-service, the outbox, both sagas, the chassis, mesh and tracing, the supply-chain gate." },
   { lead: "Partial", text: "the schema registry, shipped as a demonstrator on one topic." },
   { lead: "Deferred", text: "the big-bang rewrite, full event sourcing, the circuit breaker — each for a stated reason." },
   { lead: "Newman-led", text: "the incremental playbook, with Richardson's pattern map as the catalog we score against." }],
  "The scorecard. We followed Sam Newman's incremental playbook, and with the system finished we lay the work against the broader pattern catalog (Richardson's map is the most complete) and give each one a verdict. In blue, used: the strangler, bounded contexts, database-per-service, the outbox, both saga styles, the chassis, mesh and tracing, and the supply-chain gate. In amber, partial: the schema registry, shipped as a demonstrator. In grey, deferred for a stated reason: the big-bang rewrite, full event sourcing, and the circuit breaker. The rule the whole scorecard reflects: a pattern is used when it solves a pain the system has, and skipped, on the record, when it does not.");

leadSlide("REFLECTION · OVER-DECOMPOSITION", "The seductive failure: more services is not better",
  [{ lead: "Both ends are bad", text: "the coupled monolith on the left; nanoservice sprawl on the right, which looks like progress and is worse." },
   { lead: "The distributed monolith", text: "a service per class, all chatty over the network — every cost of microservices, all the coupling of a monolith, plus latency." },
   { lead: "We stopped at six", text: "plus a gateway, because the domain has six bounded contexts — not to reach a number." },
   { lead: "The rule", text: "split a context further only when a real internal seam demands it; finer-grained is a destination, never a goal." }],
  "The anti-pattern that wrecks more microservices migrations than any other. Both ends of the decomposition spectrum are bad. The coupled monolith is on the left. Nanoservice sprawl is on the right, and it is worse because it looks like progress: a service per entity, all chatty over the network, is a distributed monolith — every operational cost of microservices with all the coupling of a monolith restored, plus network latency on every call. We stopped at six services plus a gateway because the domain has six bounded contexts. The governing rule: split a context further only when a real internal seam demands it, never to hit a count. Finer-grained is somewhere you may arrive, not somewhere to aim.");

leadSlide("REFLECTION · HORIZONS", "What a production push would add",
  [{ lead: "Close the delivery loop", text: "an image-registry push, a GitOps operator, Istio weighted canary." },
   { lead: "Durable sagas", text: "swap the in-memory coordinator for an LRA/Narayana implementation." },
   { lead: "Observability's corners", text: "node-level log shipping into Loki, Kafka-aware tracing for the saga hops." },
   { lead: "Security past the gate", text: "secrets management, image signing, provenance attestation." },
   { lead: "Each is a bounded increment", text: "named and scoped — the only kind of future work a strangler mindset should produce." }],
  "The remaining work, scoped rather than waved at. A production push would close the delivery loop with a registry push and a GitOps operator, swap the in-memory saga coordinator for a durable LRA implementation that survives a crash, fill observability's remaining corners with node-level log shipping and Kafka-aware tracing, and go past the supply-chain gate to secrets management and image signing. None of these is a gap in the argument; each is a bounded next increment with a clear trigger. That is the only kind of future work a strangler-fig mindset should produce — named, costed, and waiting for the load that justifies it, rather than built on speculation.");

// ===== APPENDIX =============================================================
// local helper: two book figures side by side (reference material).
function twoUp(eyebrow, title, pngL, pngR, capL, capR, notes) {
  const s = S();
  addContentTitle(s, eyebrow, title);
  const colW = 5.85, maxH = 4.05, y0 = 1.95, gap = 0.39;
  [[pngL, capL, 0.62], [pngR, capR, 0.62 + colW + gap]].forEach(([png, cap, x]) => {
    s.addImage({ path: `${PNG}/${png}.png`, x, y: y0, w: colW, h: maxH, sizing: { type: "contain", w: colW, h: maxH } });
    if (cap) s.addText(cap, { x, y: y0 + maxH + 0.06, w: colW, h: 0.4, fontFace: FONT.body, fontSize: 11, italic: true, color: COLOR.caption, align: "center", valign: "top", margin: 0 });
  });
  addNotes(s, notes);
  return s;
}

divider("A", "Appendix", "Reference material — keep it open in another window.",
  "The appendix is reference, not narrative: the demo matrix, the runnable-examples catalogue, the six extractions at a glance, the behaviour-equivalence contracts, the CI gates, the stack and versions, a glossary, and a few background figures from the book. Pull it up alongside the repository rather than reading it front to back.");

{
  const s = S();
  addContentTitle(s, "APPENDIX · DEMOS", "The demo matrix");
  addStatusTable(s, [
    { code: "demo-equivalence", name: "compose", purpose: "Runs the Newman behaviour-equivalence suite against the current topology through the proxy." },
    { code: "demo-cutover", name: "compose", purpose: "Flips a seam old->new and proves the suite stays green across the flip; reversible." },
    { code: "demo-notification-cutover", name: "compose+kafka", purpose: "The async outbox->Kafka extraction cutover." },
    { code: "demo-inventory-cutover", name: "compose+grpc", purpose: "The gRPC-seam extraction, with CDC backfill during the window." },
    { code: "demo-payment-cutover", name: "compose+kafka", purpose: "The choreographed payment saga cutover." },
    { code: "demo-shipping-cutover", name: "compose+kafka", purpose: "The orchestrated shipping saga (Camel Saga EIP) cutover." },
    { code: "demo-order-cutover", name: "compose+kafka", purpose: "The CQRS order extraction; the monolith is decommissioned." },
    { code: "demo-schema-registry", name: "compose+apicurio", purpose: "Avro + Apicurio contract-first event evolution demonstrator." },
    { code: "demo-final-topology", name: "compose (full)", purpose: "The end state: six services + gateway, monolith gone." },
  ], { colW: [3.30, 2.55, 6.24], rowH: 0.42, fontSize: 12 });
  addNotes(s, "Nine demo scripts, each a runnable proof of one claim the deck makes. The middle column is the infrastructure tier each needs — most run on the compose stack, with the event-driven ones also needing Kafka, the inventory seam needing gRPC, and the schema-registry demo needing Apicurio. demo-equivalence and demo-final-topology are the two to reach for first: one proves the current system behaves, the other brings up the finished topology. The per-seam cutover scripts each walk one extraction, so they double as a guided tour of a single pattern. Every script is in demos/ in the repository and is the same code CI runs, so 'it passed in the demo' and 'it passed in CI' mean the same thing.");
}

{
  const s = S();
  addContentTitle(s, "APPENDIX · EXAMPLES", "The runnable examples");
  addStatusTable(s, [
    { code: "00-monolith", name: "Spring Boot", purpose: "The reference monolith: six contexts, one schema, planted smells, three-tier tests." },
    { code: "01-strangler-proxy", name: "Camel", purpose: "The content-based edge router; flag-gated during the migration." },
    { code: "02-review-service", name: "Quarkus", purpose: "Extraction 1 — the walking skeleton; two-phase migration." },
    { code: "03-notification-service", name: "Quarkus", purpose: "Extraction 2 — async outbox -> Kafka, SmallRye consumer, WebSocket push." },
    { code: "04-inventory-service", name: "Quarkus", purpose: "Extraction 3 — gRPC seam, CDC backfill (Debezium, transition-only)." },
    { code: "05-payment-service", name: "Quarkus", purpose: "Extraction 4 — choreographed saga over Kafka." },
    { code: "06-shipping-service", name: "Quarkus+Camel", purpose: "Extraction 5 — orchestrated saga (Camel Saga EIP)." },
    { code: "07-order-service", name: "Quarkus", purpose: "Extraction 6 — CQRS write/read split; monolith decommissioned." },
    { code: "08-graphql-gateway", name: "Quarkus", purpose: "SmallRye GraphQL aggregation gateway federating the six services." },
    { code: "09-schema-registry-demo", name: "Quarkus", purpose: "Avro + Apicurio schema registry, contract-first events." },
    { code: "10-supply-chain", name: "syft+grype", purpose: "SBOM + CVE scan + policy-as-code gate (verified live)." },
  ], { colW: [3.20, 1.95, 6.94], rowH: 0.38, fontSize: 11.5 });
  addNotes(s, "The eleven runnable examples, which are the spine of the book — each chapter's claims live in one of these directories. The first two are the starting point and the proxy; the next six are the extractions in order, each demonstrating a specific Quarkus or Camel strength as well as a decomposition pattern; the last three are the aggregation gateway, the schema-registry demonstrator, and the supply-chain gate. Every example has its own README and runs with mvn quarkus:dev, or mvn spring-boot:run for the monolith. Read this table as a map from a pattern you care about to the smallest runnable thing that shows it. The numbering is also the rough reading order through the book.");
}

{
  const s = S();
  addContentTitle(s, "APPENDIX · EXTRACTIONS", "The six extractions at a glance");
  addStatusTable(s, [
    { code: "1 · Review", name: "Strangler, first seam", purpose: "Walking skeleton; two-phase Spring->Quarkus. (ch.15)   DONE" },
    { code: "2 · Notification", name: "Async outbox -> Kafka", purpose: "Transactional outbox, SmallRye consumer, WebSocket. (ch.17)   DONE" },
    { code: "3 · Inventory", name: "gRPC seam + CDC", purpose: "Debezium backfill, retired at cutover; saga-lite compensation. (ch.19)   DONE" },
    { code: "4 · Payment", name: "Choreographed saga", purpose: "Event choreography; compensation via choreography. (ch.23)   DONE" },
    { code: "5 · Shipping", name: "Orchestrated saga", purpose: "Camel Saga EIP; explicit compensation leg. (ch.24)   DONE" },
    { code: "6 · Order", name: "CQRS; monolith gone", purpose: "Write/read split; the last and hardest; decommission. (ch.26)   DONE" },
  ], { colW: [2.75, 3.05, 6.29], rowH: 0.52, fontSize: 12.5 });
  addNotes(s, "The six extractions, in the order the coupling ladder set. Review first, because it is read-mostly and low-coupling — the right seam to prove the whole loop on. Then notification, inventory, payment, and shipping, each raising the bar and introducing its data or coordination pattern. Order last, because it is the core aggregate everything else depends on, extracted only once inventory, payment, and shipping already stood alone — and its cutover is what finally decommissioned the monolith. Every row is DONE: all six bounded contexts are now their own service, the strangler is complete, and the monolith is frozen in the repository as the permanent 'before'. The chapter numbers point at the full write-up for each.");
}

leadSlide("APPENDIX · CONTRACTS", "The behaviour-equivalence suite",
  [{ lead: "One Newman collection", text: "about 53 assertions across seven context-contract folders — the observable behaviour, pinned." },
   { lead: "Review, Notification, Inventory, Payment, Shipping, Order", text: "one contract folder each, plus a GraphQL Gateway contract — seven in all." },
   { lead: "Bounded-wait for the async seams", text: "terminal-status-within-budget assertions let a synchronous contract survive the move to events." },
   { lead: "Run everywhere", text: "locally via demo-equivalence, and in CI on every push as the per-seam gates." }],
  "The behaviour-equivalence suite in one place, because it is the net the whole method hangs on. It is a single Newman/Postman collection of roughly fifty-three assertions, organised into seven folders — one per context contract, plus the GraphQL gateway. Each folder pins the observable behaviour of its seam: the status codes, the payloads, the terminal states a caller can see. The async seams use bounded-wait assertions, which wait a bounded time for a terminal status rather than demanding an immediate result, so the same contract survives the move from synchronous to event-driven processing. The collection runs locally through demo-equivalence and in CI as the per-seam gates, which is what makes a behavioural regression fail the build on every push.");

{
  const s = S();
  addContentTitle(s, "APPENDIX · CI GATES", "The pipeline, job by job");
  addStatusTable(s, [
    { code: "equivalence-gate", name: "push / PR", purpose: "The monolith baseline — the Newman suite green on the reference behaviour." },
    { code: "notification / inventory /", name: "push / PR", purpose: "payment / shipping -equivalence-gate — one per async-extracted seam." },
    { code: "order-gateway-contract-gate", name: "push / PR", purpose: "The CQRS + GraphQL seam, as a contract check (monolith decommissioned)." },
    { code: "schema-registry-gate", name: "push / PR", purpose: "Builds and tests the Avro + Apicurio demonstrator." },
    { code: "supply-chain.yml", name: "push / PR", purpose: "SBOM + grype scan, fail-on High — verified green live." },
    { code: "pages.yml", name: "push to main", purpose: "Builds and deploys the Jekyll documentation site." },
  ], { colW: [3.65, 1.95, 6.49], rowH: 0.52, fontSize: 12 });
  addNotes(s, "The pipeline, enumerated. code-ci.yml holds seven behaviour gates: the monolith baseline, one equivalence gate per async-extracted seam, the order-and-gateway contract gate, and the schema-registry gate. Each is path-filtered, so a change under one service runs that service's gate without rebuilding the rest, and each boots real infrastructure — Postgres, and Kafka where the seam is event-driven — before running Newman. Alongside them, supply-chain.yml is the additive provenance gate that builds a service, generates its SBOM, and scans it, which ran green live. pages.yml builds and publishes the documentation site. What CI does not do is build or push images or deploy — that is named as deferred, not implied.");
}

{
  const s = S();
  addContentTitle(s, "APPENDIX · STACK", "Stack and versions");
  addStatusTable(s, [
    { code: "JDK 25", name: "runtime", purpose: "Language runtime (SDKMAN); native via GraalVM/Mandrel." },
    { code: "Quarkus 3.40.1", name: "services", purpose: "The target runtime — fast startup, Dev Services, native builds." },
    { code: "Spring Boot 4.1", name: "monolith", purpose: "The reference 'before'." },
    { code: "Apache Camel 4.22", name: "routing", purpose: "Strangler proxy and the orchestrated saga (Java DSL)." },
    { code: "Kafka 4.3 (KRaft)", name: "events", purpose: "Outbox relays and saga choreography." },
    { code: "PostgreSQL 18", name: "data", purpose: "Per-service schemas, the outbox." },
    { code: "Apicurio 3.3.3", name: "contracts", purpose: "Avro schema registry." },
    { code: "Debezium 3.7", name: "CDC", purpose: "Inventory backfill — transition-only." },
    { code: "Istio 1.31 / LGTM", name: "operate", purpose: "Mesh + mTLS; Loki/Grafana/Tempo/Mimir + OpenTelemetry (minikube)." },
  ], { colW: [3.05, 1.95, 7.09], rowH: 0.42, fontSize: 11.5 });
  addNotes(s, "The stack, pinned. JDK 25 is the runtime, with native images via GraalVM or Mandrel. Quarkus 3.40.1 runs the six services; Spring Boot is the monolith we started from (3.5 then, 4.1 now); Camel 4.22 is the strangler proxy and the orchestrated saga. Kafka in KRaft mode is the event backbone behind the outbox relays and the choreographed sagas; PostgreSQL holds each service's own schema and its outbox. Apicurio is the Avro registry; Debezium handled the inventory backfill and was retired afterward. Istio and the LGTM stack — Loki, Grafana, Tempo, Mimir — with OpenTelemetry provide the mesh and observability on minikube. These versions drift; the repository's decisions log is the source of truth, and the numbers are worth re-checking against upstream before a talk.");
}

{
  const s = S();
  addContentTitle(s, "APPENDIX · GLOSSARY", "Terms used in this deck");
  addTwoColBullets(s,
    [{ text: "Bounded context — a domain boundary with its own model and ubiquitous language." },
     { text: "Strangler fig — incremental replacement behind a proxy, old and new side by side." },
     { text: "Anti-corruption layer — translation that keeps a legacy model out of a new service." },
     { text: "Transactional outbox — write the business row and an event row in one local transaction." },
     { text: "CDC — change data capture; tailing the write-ahead log to propagate changes." },
     { text: "Saga — a cross-service workflow coordinated by events or an orchestrator, with compensation." }],
    [{ text: "Choreography / orchestration — the two saga styles: reactive, vs a named coordinator." },
     { text: "Compensation — a semantic inverse that undoes a committed step (no distributed rollback)." },
     { text: "CQRS — command/query responsibility segregation; a separate read model." },
     { text: "ACID -> ACD — relaxing one cross-service transaction to per-service consistency." },
     { text: "Chassis — the cross-cutting foundation (config/health/metrics/tracing) you opt into." },
     { text: "SBOM — software bill of materials; the component list a scanner reads." }],
    { fontSize: 12.5 });
  addNotes(s, "A glossary for the terms this deck leans on, so nobody has to infer them from context. The decomposition terms — bounded context, strangler fig, anti-corruption layer — come from the seam-finding and strangler sections. The data terms — transactional outbox, change data capture, CQRS, ACID-to-ACD — come from the data section. The coordination terms — saga, choreography and orchestration, compensation — come from the coordinating section. Chassis and SBOM come from the operating and delivering sections. These are the vocabulary of the field as much as of this project; the book's own index carries fuller definitions, and each term is introduced where it first appears in the deck.");
}

twoUp("APPENDIX · FIGURES", "Background figures (1 of 2)",
  "r-coupling-ladder", "r-two-phase-migration",
  "The coupling ladder — sequencing the extractions", "Review: the two-phase migration",
  "Two reference figures for the appendix, redrawn here in the house style. The coupling ladder is the sequencing tool from the seam-finding section: contexts are ranked by inbound dependency and rate of change, which fixes the order of extraction so each cut is only as hard as it needs to be and earlier cuts de-risk later ones — five of its seven rungs have a live example in the monolith, and the top two are what the extracted services are built to reach. The two-phase migration figure is the per-service recipe: phase A lifts the code onto Quarkus with Spring-compatibility extensions to get the suite green fast, and phase B refactors to idiomatic Quarkus — the measured before/after (startup roughly thirty times faster, resident memory about four times smaller, going to a native image) is where the case for Quarkus gets made with numbers.");

twoUp("APPENDIX · FIGURES", "Background figures (2 of 2)",
  "r-context-map", "r-chassis-scorecard",
  "The context map — the six bounded contexts", "The chassis scorecard — capabilities by status",
  "Two more reference figures, redrawn in the house style. The context map shows the six bounded contexts and the relationships between them — the domain picture the seam-finding section turns into service boundaries. Order holds four outbound edges: a conformist relationship to inventory with the anti-corruption layer still missing, and three direct in-process calls to payment, shipping, and notification inside one transaction; all six share a single Postgres schema, and review has no outbound edges, which is why it extracted first. The chassis scorecard tracks which chassis capabilities each service has turned on — configuration, REST client, native, and dev-mode in use; health and security partial; fault tolerance, metrics, and OpenAPI deferred — the opt-in model from the communication-and-chassis section made into a checklist.");

// ===== CLOSING ==============================================================
{
  const s = S();
  addContentTitle(s, "CLOSING", "The book, in one arc");
  leadBullets(s, [
    { lead: "One monolith to six services", text: "a Spring Boot monolith, strangled one seam at a time into Quarkus services that own their data and coordinate without a conductor." },
    { lead: "A method that verifies itself", text: "the ADLC is safe because an automated behavioural gate runs on every push." },
    { lead: "Clear about its limits", text: "every deferral is named and every 'verified' is backed by a run." },
    { lead: "Fit over fashion", text: "patterns were used where they solved a real pain, and the migration stopped when it ran out of real seams." },
  ], { fontSize: 17 });
  addNotes(s, "The one-paragraph recap. A coupled monolith became six Quarkus services that own their data and coordinate through sagas, built incrementally behind a strangler proxy with a behavioural gate green the whole way, driven by the ADLC. Each chapter states what was verified and what was deferred. The through-line is discipline in three forms: incremental change behind a reversible flag, automated behavioural verification on every push, and restraint about which patterns and how many services the system needed. That combination is what makes agent-driven modernization something you can defend in a review rather than something you hope works.");
}
{
  const s = S();
  addContentTitle(s, "CLOSING · GO DEEPER", "Where to go from here");
  leadBullets(s, [
    { lead: "The book", text: "the full 32-chapter tutorial site — every chapter with a runnable example and a verification footer." },
    { lead: "The repo", text: "the monolith, the six services, the proxy, the equivalence suite, and the deploy tree — runnable on Docker Engine and minikube." },
    { lead: "Start where your pain is", text: "a smell in the monolith section, the seam-finding method, or the supply-chain gate you can run today." },
  ], { fontSize: 17 });
  addCaption(s, "Modernizing Enterprise Applications — 201 implementation deep-dive · " + REV);
  addNotes(s, "Where to go next. The book site carries all thirty-two chapters, each with a runnable example and a verification footer that states how far to trust it. The repository runs on Docker Engine and minikube — the monolith, the six services, the proxy, the equivalence suite, and the Kubernetes deploy tree. The most useful entry point is wherever your own pain is: pick a smell from the monolith section, apply the seam-finding method to your own domain, or run the supply-chain gate against your own artifacts this afternoon and see what it reports. Thank you.");
}

pres.writeFile({ fileName: OUT })
  .then(p => console.log("WROTE", p, "slides:", pageNum))
  .catch(e => { console.error(e); process.exit(1); });
