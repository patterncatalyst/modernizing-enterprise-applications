// deck.js — "Modernizing Enterprise Applications" — 101 (the ideas).
// Concept-forward companion, datamesh-101 style. No deep code — that is the 201.
// Red Hat house style, 16:9. Build: NODE_PATH=./node_modules node deck.js
"use strict";

const H = require("./deck-helpers.js");
const {
  COLOR, FONT, W, PNG, ASSETS,
  newDeck, addFooter, addContentTitle, addBullets, addTwoColBullets,
  addStatusTable, addCaption, addCodeSlide, addSectionDivider, addNotes,
} = H;

const OUT = "Modernizing_101-r1.0.pptx";
const REV = "r1.0";

const pres = newDeck();
pres.title = "Modernizing Enterprise Applications — 101 (the ideas)";
let pageNum = 0;

function S() { const s = pres.addSlide(); pageNum += 1; addFooter(s, pageNum); return s; }
function divider(code, title, subtitle, notes) {
  const s = pres.addSlide(); pageNum += 1; addSectionDivider(s, code, title, subtitle); addNotes(s, notes);
}

// ---- local helper: bold-lead bullets (bold subject run + normal text run) ----
function leadBullets(slide, items, opts = {}) {
  const x = opts.x ?? 0.62, y = opts.y ?? 1.95, w = opts.w ?? 12.09, h = opts.h ?? 4.7;
  const fontSize = opts.fontSize ?? 17;
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
  s.addText("MODERNIZING ENTERPRISE APPLICATIONS · 101", { x: 6.00, y: 1.98, w: 6.90, h: 0.34,
    fontFace: FONT.title, fontSize: 14, bold: true, color: COLOR.red, charSpacing: 5, align: "left", valign: "middle" });
  s.addText([{ text: "The ideas:", options: { breakLine: true } },
             { text: "monolith to", options: { breakLine: true } },
             { text: "microservices" }], {
    x: 5.95, y: 2.42, w: 6.95, h: 2.70, fontFace: FONT.title, fontSize: 50, bold: true, color: COLOR.ink, align: "left", valign: "top" });
  s.addText("Why modernize, and how an AI-driven lifecycle strangles a Spring Boot monolith into Quarkus + Camel services — safely, one seam at a time.",
    { x: 6.00, y: 5.15, w: 6.80, h: 1.05, fontFace: FONT.body, fontSize: 16, italic: true, color: COLOR.caption, align: "left", valign: "top" });
  s.addText(REV, { x: 11.85, y: 6.10, w: 0.95, h: 0.30, fontFace: FONT.mono, fontSize: 11, color: COLOR.caption, align: "right", valign: "middle" });
  try { s.addImage({ path: `${ASSETS}/logo-candidate-2.png`, x: 11.10, y: 6.78, w: 1.55, h: 0.37 }); } catch (e) {}
  addNotes(s, "This is the 101 — the ideas deck. It makes the conceptual case for modernization and explains the method that drives it, without going into the implementation; the running code, the service-by-service detail, and the live demos are the 201's job. The whole argument is one claim taken seriously: a coupled Spring Boot monolith can become Quarkus and Camel microservices without a big-bang rewrite and without breaking behaviour, if you move one seam at a time behind an automated behavioural gate. The migration was driven by an AI Development Lifecycle, which the second section covers. No prior Quarkus knowledge is assumed. Everything here is drawn from a real running reference system you can build and drive yourself.");
}

// ===== AGENDA ================================================================
{
  const s = S();
  addContentTitle(s, "101 · THE IDEAS", "The whole argument, in one sitting");
  addTwoColBullets(s,
    ["Why modernize — and when to leave it alone", "The ADLC — the method that makes it safe", "The strangler fig — grow new around old", "Finding the seams in the domain"],
    ["Data, ownership, and coordination as ideas", "The chassis you opt into, and operating the result", "The modernization, made measurable", "Where to stop — and where to go next"]);
  addNotes(s, "The 101 runs in three acts. The first is the case for change and the method that makes agent-driven change safe — this is where the argument is won or lost, so it gets the most time. The second walks the migration as a set of ideas: strangle the monolith a seam at a time, find those seams in the domain, give each service its own data, and coordinate them without a central conductor. The third covers operating the result and reviewing which ideas earned their place. If you have ten minutes, the ADLC loop and the measurable before-and-after carry the whole case. For the running system behind every one of these ideas, point people at the 201.");
}

// ===== ACT A — WHY & HOW =====================================================
divider("A", "Why, and How", "The case for change — and the method that makes it safe.",
  "Two questions open the deck. First, why modernize at all, given it costs money and risk? Second, once you decide to, how do you change a system that must keep serving traffic without betting the business on a rewrite? The answer to the second is the method — the ADLC — and it is the idea that makes everything after it possible.");

leadSlide("WHY MODERNIZE · THE CASE", "Modernize because change got expensive",
  [{ lead: "The monolith is usually where the value is", text: "a working system encodes years of business rules; the problem is that every change is slow and coupled to everything else." },
   { lead: "The target is independent deployability", text: "not a service count — ship the parts that change at different rates without redeploying the whole." },
   { lead: "Sam Newman's playbook drives this book", text: "incremental decomposition over a big-bang rewrite — Monolith to Microservices, one seam at a time." },
   { lead: "A rewrite bets the company on a flag day", text: "it discards encoded rules and swaps a known system for an unproven one in a single cutover. We never take that bet." }],
  "The opening argument. Modernization earns its cost only when the rate of change the business needs exceeds what the current architecture can absorb safely. The target to keep in view is independent deployability — the ability to ship one part of the system without redeploying the rest — not a headcount of services, a distinction that returns at the very end when we talk about over-decomposition. The governing voice through the whole book is Sam Newman: decompose incrementally, keep the system serving traffic the entire time, and never stake the business on a single rewrite cutover. Everything that follows is a way to make that incremental path safe.");

leadSlide("WHY MODERNIZE · RESTRAINT", "And when not to modernize at all",
  [{ lead: "Stable and low-value", text: "a system that rarely changes and carries little strategic weight is cheapest left exactly as it is." },
   { lead: "Encapsulate before you extract", text: "put an API in front of a component you are not ready to touch; buy the option without paying for the move." },
   { lead: "No seam, no split", text: "if a context has no real internal boundary, forcing one in produces coupling, not independence." },
   { lead: "Modernization is a means", text: "the test is whether a specific change gets safer or cheaper — never whether the result looks more modern." }],
  "Restraint is half the discipline, and it belongs in the ideas deck because the most common way these projects fail is treating modernization as a virtue and applying it everywhere. A stable, low-value system is cheapest left alone. A component you are not ready to extract can still be wrapped behind an API, which buys the option to move later without paying for it now. And forcing a split where the domain has no real seam manufactures coupling rather than removing it. The question for every candidate is narrow: does a change the business actually needs get safer or cheaper? If the answer is no, the right move is to leave it running.");

diagramBulletsSlide("WHY MODERNIZE · STRATEGY", "Pick a strategy by value and risk",
  "r01-modernization-2x2",
  [{ lead: "Retain / encapsulate", text: "low value, low risk — leave it running, wrap it behind an API." },
   { lead: "Rehost / replatform", text: "high value, low risk — quick wins: containerize, move onto Kubernetes." },
   { lead: "Refactor / re-architect", text: "high value, high change — the strangler fig, done incrementally. This book lives here." },
   { lead: "Retire / replace", text: "low value, high risk — sunset it, or rebuild only if the value justifies it." }],
  "The strategy grid. The point is that modernization is not one decision for the whole system; you score each capability by business value against the change and risk of touching it. Low-value, low-risk components get encapsulated and left alone. High-value, low-risk ones are quick replatform wins. The strangler fig lives in the top-right quadrant — high value and high change, the parts worth refactoring carefully and incrementally — and that is where the migration happens. Run this grid over a real portfolio and most components sort themselves into leave-alone or quick-win, leaving a handful that warrant the strangler treatment. That triage is the first idea.");

// ===== ACT A — THE ADLC ======================================================
leadSlide("THE ADLC · FROM SDLC", "The big idea: the human moves up a level",
  [{ lead: "The classic lifecycle assumes a human authors each line", text: "the ADLC assumes an agent generates the change and a human frames, gates, and reconciles it." },
   { lead: "That only works with an automated answer", text: "to the question 'is this change correct?' — otherwise every merge is a leap of faith." },
   { lead: "So the method is built around a gate", text: "not around trust in the model's output." },
   { lead: "The human's job shifts", text: "from typing code to framing the seam, setting the acceptance bar, and checking what ran against what was claimed." }],
  "This is the section the original deck never had, and it is the centre of gravity for the whole approach. In the classic software lifecycle the human is the author; in the AI Development Lifecycle the human becomes the framer and the gatekeeper while the agent is the author. That reassignment is only responsible if correctness can be checked without a person reading every diff — which is why the method is built around an automated gate rather than around trust in the model. The economic driver underneath it is simple: when generating a change becomes cheap, the cost and the risk both move to verification, so that is where the method puts its weight. Hold that thought — the gate is the next two slides.");

diagramBulletsSlide("THE ADLC · THE LOOP", "Five phases, every increment",
  "r02-adlc-loop",
  [{ lead: "Frame", text: "name the seam and the coupling smell it cures." },
   { lead: "Plan", text: "sequence the work, record the decisions, set the acceptance bar." },
   { lead: "Generate", text: "agents, skills, and MCP tools author the change." },
   { lead: "Verify", text: "the behaviour-equivalence suite must stay green, in CI." },
   { lead: "Reconcile", text: "a status footer records what was claimed against what actually ran." }],
  "The loop runs once per increment — per seam, in this migration. Frame names the boundary and the specific coupling problem it fixes. Plan sequences the steps and sets the bar the result must clear. Generate is where the agent does the authoring, using skills and tools. Verify runs the behaviour-equivalence suite, and a red suite blocks the merge — that is the gate. Reconcile is the step teams skip: a written record of what was claimed versus what was actually run, which keeps the gap between documentation and reality from quietly widening. The loop feeds forward — each increment's reconcile informs the next frame. Notice the whole thing is organised around Verify; the other four phases exist to make that gate meaningful.");

leadSlide("THE ADLC · THE SAFETY NET", "The gate is a behaviour-equivalence suite",
  [{ lead: "One suite asserts observable behaviour", text: "a collection of API checks the system must satisfy, old or new — the contract, not the implementation." },
   { lead: "Every cutover has to keep it green", text: "in both positions, before the old code is removed — correctness becomes objective, not a judgement call." },
   { lead: "That is what makes agent-authored change mergeable", text: "a red suite blocks the merge, so a generated change is as reviewable as any other — arguably more." },
   { lead: "Strip the suite out and the method collapses", text: "every remaining step becomes a leap of faith. Keep it, and the whole approach holds." }],
  "This is the idea everything else rests on, so it gets its own slide. The gate is a behaviour-equivalence suite — a set of checks that assert what the system does from the outside, independent of how it is built. Every seam cutover has to keep that suite green in both positions, old and new, before the old module is removed, which turns correctness from a judgement call into an objective check that runs on every push. That is precisely what makes it safe to let an agent lift a service, refactor it, and cut traffic over to it: not trust in the agent, but a gate that goes red the instant behaviour drifts. Remove the suite and every other part of the method becomes a leap of faith; keep it, and agent-driven change is as reviewable as any other change.");

// ===== ACT B — THE MIGRATION =================================================
divider("B", "The Migration", "Grow the new system around the old, one seam at a time.",
  "With the method in place, the migration itself is a sequence of ideas: strangle the monolith a seam at a time, find those seams in the domain rather than inventing them, give each extracted service its own data, and let the services coordinate without a central conductor. This act walks those ideas; the 201 walks the code that implements each one.");

diagramBulletsSlide("THE STRANGLER FIG · THE IDEA", "A proxy in front, one seam out at a time",
  "r05-strangler-proxy",
  [{ lead: "Put a proxy in front of the monolith", text: "it routes each request to the old system or the new service, chosen by a flag." },
   { lead: "Extract one seam at a time", text: "stand up a service, flip its flag, and keep the equivalence suite green across the flip." },
   { lead: "Every cutover is reversible", text: "flip the flag back until the old module is decommissioned — the system serves traffic throughout." },
   { lead: "Six seams, in dependency order", text: "review, notification, inventory, payment, shipping, order — the last one is the hardest." }],
  "The strangler fig is the central migration idea, and it is Newman's incremental path made concrete. A proxy sits in front of the monolith and routes each request either to the old code or to a newly extracted service, chosen by a feature flag. You extract one seam at a time: stand up the new service, flip its flag, and require the behaviour-equivalence suite to stay green across the flip. Because it is a flag, every cutover is reversible right up until the old module is removed, and the system keeps serving traffic the entire time. This project did it six times, in dependency order, leaving the core order context — the hardest — for last. The name is apt: the new system grows around the old until the old can be cut away.");

diagramBulletsSlide("FINDING THE SEAMS", "Seams are discovered, not invented",
  "r04-event-storm-seams",
  [{ lead: "Event storming maps the domain", text: "lay out the business events, then cluster them by the aggregate that owns them." },
   { lead: "The clusters are the bounded contexts", text: "order, inventory, payment, shipping, notification, review — six real boundaries in the domain." },
   { lead: "A real seam has a clean boundary", text: "the data crossing it is data the domain already exchanged — so the translation layer has almost nothing to do." },
   { lead: "Service count follows the domain", text: "you stop when you run out of real seams, not when you hit a number." }],
  "Before you can strangle a seam, you have to find it, and the idea here is that seams are discovered in the domain rather than drawn on an architecture diagram. Event storming is the technique: get the domain events on a wall and cluster them by the aggregate that owns them. Those clusters are the bounded contexts, and in this domain there are six. The test of a real seam is that its boundary is clean — the data crossing it is data the business already passed across that line, which is why a well-placed anti-corruption layer ends up with almost nothing to translate. This is also where the service count comes from: six services because the domain has six contexts, not because six was a target. You stop when you run out of real seams.");

leadSlide("DATA ACROSS THE SEAM", "The hard part is the data, not the code",
  [{ lead: "Each service owns its data", text: "database-per-service — the shared schema underneath the monolith is what the migration really unwinds." },
   { lead: "The transactional outbox replaces the dual write", text: "write the business row and the event in one local transaction, then relay it — so a crash can't lose the event." },
   { lead: "Change data capture is a bridge, not a destination", text: "it backfills a new service's data during cutover, then gets torn down." },
   { lead: "CQRS where read and write shapes diverge; ACID becomes ACD", text: "across services you trade a distributed transaction for eventual consistency with compensation." }],
  "Decomposition is really about data, and these are the ideas that make splitting a shared database survivable. Database-per-service is the goal — six contexts that began sharing one schema each ending as the sole owner of their own. The transactional outbox solves the problem every tutorial skips: you cannot atomically update your database and publish to a broker, so you write the event into an outbox table in the same local transaction and relay it afterward, which means a crash between the two steps cannot lose it. Change data capture appears as a bridge that backfills a new service during cutover and is then retired, not a permanent dependency. And across services the single ACID transaction gives way to eventual consistency with compensation — ACID becomes what we call ACD. The 201 shows each of these in code.");

diagramBulletsSlide("COORDINATING", "Two ways to run a workflow across services",
  "r07-choreo-vs-orchestr",
  [{ lead: "Choreography — no conductor", text: "services react to each other's events; loosely coupled and resilient, but no single place describes the whole flow." },
   { lead: "Orchestration — a route conducts", text: "one component sequences the steps and their compensations; legible and central, but in the critical path." },
   { lead: "Compensation, not rollback", text: "across services you undo a committed step with a compensating action — there is no distributed rollback." },
   { lead: "You will use both", text: "the choice is per workflow — payment went choreographed, shipping orchestrated — not one correct style." }],
  "Once services own their data, a workflow that spans them — like a checkout — needs coordination, and there are two styles worth knowing. In choreography, services react to each other's events; it is loosely coupled and resilient, but no single place tells you what the whole workflow is doing. In orchestration, one component explicitly sequences the steps and their compensations; it is legible and centralised, but the conductor sits in the critical path. The key idea underneath both is that across services there is no distributed rollback — you undo a committed step with a compensating action, a saga. This project used both, on the same checkout, so the lesson lands: you will reach for each in different places for different reasons, rather than declaring one correct.");

// ===== ACT C — OPERATING & REFLECTION ========================================
divider("C", "Operating & Reflection", "Run the result, measure the win, and know when to stop.",
  "The last act covers what you get once the migration is done: a chassis that makes many services tractable, an operational baseline from the platform and the mesh, a measurable improvement you can put a number on, and the discipline to know which patterns earned their place and where to stop.");

leadSlide("THE CHASSIS", "The chassis in 2026 is a platform you opt into",
  [{ lead: "The chassis is the cross-cutting foundation", text: "configuration, health, metrics, tracing, graceful shutdown — what every service needs and no service's reason to exist." },
   { lead: "With Quarkus and MicroProfile you declare it", text: "add a dependency and the capability appears — you do not build or maintain a framework." },
   { lead: "The platform now answers the operational questions", text: "identity, traffic, and discovery come from Kubernetes and the mesh — the roles the Netflix-OSS stack once filled in-app." },
   { lead: "Native builds come with the opt-in", text: "fast startup and a small footprint, by declaring a profile — not a rewrite." }],
  "The chassis is the idea that makes running many services tractable, and in 2026 it has changed character. It is the cross-cutting foundation every service needs — configuration, health probes, metrics, tracing, graceful shutdown — and the shift is that with Quarkus and MicroProfile you acquire it by declaring a dependency rather than building and maintaining it yourself. Adding an extension turns tracing on; a configuration annotation is your config; a dependency is health probes Kubernetes can read. The operational questions that a decade ago teams solved in the application with the Netflix-OSS stack — discovery, load balancing, circuit breaking — are now answered by the platform and the mesh. Native compilation comes with the same opt-in. You adopt capabilities; you do not build a framework.");

diagramBulletsSlide("OPERATING · THE MEASURABLE WIN", "The modernization, made measurable",
  "r09-supply-chain-gate",
  [{ lead: "One gate, two artifacts, same day", text: "generate a software bill of materials, scan it for known vulnerabilities, and fail on a High-or-worse finding." },
   { lead: "The monolith fails it", text: "the Spring Boot fat jar carries 4 Critical and 6 High findings — the gate fires." },
   { lead: "The modernized service passes it", text: "the extracted Quarkus service carries zero — against the same vulnerability database, the same day." },
   { lead: "A scan is a moving target", text: "clean today is not clean forever, which is why the gate runs on every push, not once at release." }],
  "Most of this deck argues the case qualitatively; this slide makes it a number. The supply-chain gate is three steps — generate a bill of materials with syft, scan it with grype, and apply a policy-as-code threshold that fails the build on any High or Critical finding. Run that same gate over the two artifacts this project produces, on the same day against the same vulnerability database: the Spring Boot monolith fat jar fails with four Critical and six High findings, and the extracted Quarkus service passes with zero. The modernization was not only a supply-chain improvement, but it was measurably one. The closing idea on the slide matters too — a scan is a point-in-time claim against a database that moves daily, which is why the gate belongs on every push rather than at release.");

diagramBulletsSlide("REFLECTION · THE PATTERNS", "Which patterns earned their place",
  "r10-pattern-scorecard",
  [{ lead: "A pattern belongs when it cures a pain you have", text: "strangler fig, database-per-service, the outbox, and both saga styles were used because the migration hit the problems they solve." },
   { lead: "Deferred is a verdict too", text: "the rewrite, full event sourcing, and the circuit breaker were left on the shelf — their justifying pains were not present." },
   { lead: "The map is Richardson's catalog", text: "walked in Newman's incremental spirit — a set of defensible judgements, not a checklist to complete." }],
  "The close re-walks the pattern catalog against the finished system and scores each one. The governing idea is that a pattern belongs when it solves a pain you actually have: the strangler fig, database-per-service, the transactional outbox, and both saga styles are here because the migration ran into exactly the problems they solve. Just as important, deferring a pattern is a real verdict — the rewrite, full event sourcing, and the circuit breaker were left on the shelf because the pains that justify their cost were not present in this system. The catalog itself is Chris Richardson's, walked in Sam Newman's incremental spirit. The scorecard is a set of defensible judgements, not a checklist to complete — which is the healthiest way to hold any pattern language.");

leadSlide("REFLECTION · WHERE TO STOP", "Over-decomposition is the seductive failure",
  [{ lead: "The far end of the spectrum is worse than the near end", text: "a monolith is too coupled; nanoservice sprawl is a distributed monolith — all the cost, all the coupling, plus a network." },
   { lead: "The tell is a coordinated release", text: "if you cannot change one service without releasing several, decomposition has made things worse, not better." },
   { lead: "Stop at context-aligned services", text: "split a context further only when a real seam inside it demands it — never to hit a number." },
   { lead: "The horizons are named and scoped", text: "GitOps, durable sagas, the remaining observability corners — each a bounded next increment, not a rewrite." }],
  "The last idea is knowing when to stop, because over-decomposition is the most seductive way these projects fail — it looks like progress. The far end of the spectrum is worse than the near end: a monolith is too coupled, but nanoservice sprawl is a distributed monolith, carrying all the operational cost of microservices and all the coupling of a monolith, with a network added between every call. The tell is simple — if you cannot change one service without coordinating a release across several, decomposition has made things worse. The rule is to stop at context-aligned services and split further only when a real seam inside a context demands it. And the real next steps — GitOps, durable sagas, the remaining observability work — are each a bounded increment, which is the only kind of future work a strangler mindset should produce.");

// ===== CLOSE =================================================================
{
  const s = S();
  addContentTitle(s, "101 · CLOSE", "One monolith, six services, a method that verifies itself");
  leadBullets(s, [
    { lead: "The claim held", text: "a coupled monolith became six Quarkus services that own their data and coordinate without a conductor — with the equivalence suite green the whole way." },
    { lead: "The method is why it was safe", text: "agent-driven change, gated by an automated behavioural check and reconciled against what actually ran, increment by increment." },
    { lead: "For the running system, see the 201", text: "the service-by-service code, the capability tour, the demo matrix, and the full appendix live there." },
    { lead: "Everything here is in the book and the repo", text: "every claim is grounded in a chapter and a runnable example you can drive yourself." },
  ]);
  addNotes(s, "To close, bring it back to the one claim. A coupled Spring Boot monolith became six Quarkus and Camel services, each owning its data and coordinating without a central conductor, with the behaviour-equivalence suite green at every step — and the reason it was safe to do that with AI agents authoring the change is the method: an automated behavioural gate and a reconcile step that records what ran, repeated one increment at a time. This deck made the case at the level of ideas. For the running system behind every idea — the service-by-service code, the capability tour, the nine demos, and the reference appendix — point people at the 201. And everything in both decks is grounded in the book and a repository of runnable examples anyone can drive themselves. That is the whole argument: modernization as incremental, verified, and measurable engineering.");
}

pres.writeFile({ fileName: OUT })
  .then((p) => console.log("WROTE", p, "slides:", pageNum))
  .catch((e) => { console.error(e); process.exit(1); });
