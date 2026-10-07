"""
diagrams.py — companion-deck diagrams for "Modernizing Enterprise Applications".

Red Hat-aligned Scene diagrams, one per high-value figure in the deck. Author
here, then run build_diagrams.py (renders SVG + Excalidraw + PNG via cairosvg).

Color by kind: svc=blue (services/clients), rest=red (edge/API surface / the
move this book takes), data=purple (stores/state), platform=teal (infra/
platform), govern=amber (policy/coordination), danger=deep-red (anti-pattern/
failure/fail-gate), neutral=grey, muted=caption grey.
"""
from dgen import Scene, PALETTE


# ---------------------------------------------------------------------------
# 1 — modernization 2x2: business value vs change/risk
# ---------------------------------------------------------------------------
def r01_modernization_2x2():
    s = Scene("r01-modernization-2x2", 1200, 660,
              title="Modernize by value and risk — not all at once",
              subtitle="The six Rs on a value / change-risk grid; this book lives in the top-right, done incrementally")
    # grid
    x0, y0, cw, ch, gap = 210, 120, 440, 215, 16
    s.label(150, y0 + 10, "HIGH", size=11, weight="bold", color=PALETTE["muted"], anchor="middle")
    s.label(150, y0 + ch + gap + ch, "LOW", size=11, weight="bold", color=PALETTE["muted"], anchor="middle")
    s.label(110, y0 + ch, "business", size=12, weight="bold", color=PALETTE["neutral"])
    s.label(110, y0 + ch + 18, "value", size=12, weight="bold", color=PALETTE["neutral"])
    # top-left: high value, low risk
    s.box(x0, y0, cw, ch, "Rehost / Replatform", ["quick wins — containerize,", "lift onto Kubernetes"], kind="platform")
    # top-right: high value, high risk
    s.box(x0 + cw + gap, y0, cw, ch, "Refactor / Re-architect", ["the strangler fig — one seam", "at a time  (this book)"], kind="rest")
    # bottom-left: low value, low risk
    s.box(x0, y0 + ch + gap, cw, ch, "Retain / Encapsulate", ["leave it running;", "wrap it behind an API"], kind="svc")
    # bottom-right: low value, high risk
    s.box(x0 + cw + gap, y0 + ch + gap, cw, ch, "Retire / Replace", ["sunset it, or buy/rebuild", "only if value justifies it"], kind="danger")
    # x axis labels
    yb = y0 + 2 * ch + gap + 34
    s.label(x0 + cw / 2, yb, "LOW change / risk", size=11, weight="bold", color=PALETTE["muted"], anchor="middle")
    s.label(x0 + cw + gap + cw / 2, yb, "HIGH change / risk", size=11, weight="bold", color=PALETTE["muted"], anchor="middle")
    s.write()


# ---------------------------------------------------------------------------
# 2 — the ADLC loop
# ---------------------------------------------------------------------------
def r02_adlc_loop():
    s = Scene("r02-adlc-loop", 1240, 620,
              title="The AI Development Lifecycle (ADLC)",
              subtitle="Five phases per increment; the behavior-equivalence suite is the gate that makes agent-driven change safe")
    y = 170
    w, h, gap = 200, 86, 32
    xs = [40 + i * (w + gap) for i in range(5)]
    phases = [
        ("Frame", ["the seam + the", "planted smell"], "govern"),
        ("Plan", ["sequence, decisions,", "acceptance bar"], "svc"),
        ("Generate", ["agents + skills", "+ MCP tools"], "rest"),
        ("Verify", ["equivalence suite", "green, in CI"], "platform"),
        ("Reconcile", ["status footer:", "claimed vs run"], "data"),
    ]
    for i, (t, ln, k) in enumerate(phases):
        s.box(xs[i], y, w, h, t, ln, kind=k)
        if i < 4:
            s.arrow(xs[i] + w, y + h / 2, xs[i + 1], y + h / 2, kind="neutral")
    # feedback arrow Reconcile -> Frame along the bottom
    fy = y + h + 70
    s.arrow(xs[4] + w / 2, y + h, xs[4] + w / 2, fy, kind="muted")
    s.arrow(xs[4] + w / 2, fy, xs[0] + w / 2, fy, kind="muted", label="every increment feeds the next")
    s.arrow(xs[0] + w / 2, fy, xs[0] + w / 2, y + h, kind="muted")
    # the gate callout under Verify
    s.panel(xs[3] - 20, y + h + 100, 300, 70)
    s.label(xs[3] - 4, y + h + 128, "the safety net", size=11, weight="bold", color=PALETTE["rest"])
    s.label(xs[3] - 4, y + h + 150, "a red suite blocks the merge", size=12, color=PALETTE["muted"])
    s.write()


# ---------------------------------------------------------------------------
# 3 — the monolith: six contexts, one schema, planted smells
# ---------------------------------------------------------------------------
def r03_monolith_contexts():
    s = Scene("r03-monolith-contexts", 1280, 620,
              title="The reference monolith: six contexts, one shared schema",
              subtitle="A fresh Spring Boot monolith whose planted coupling smells each map to a pattern that cures it")
    # monolith band
    s.panel(40, 100, 1200, 150)
    s.label(60, 124, "Spring Boot monolith — one deploy, one process", size=13, weight="bold", color=PALETTE["neutral"])
    ctxs = ["order", "inventory", "payment", "shipping", "notification", "review"]
    bw, bh, gap = 180, 64, 15
    x = 60
    for c in ctxs:
        s.box(x, 150, bw, bh, c, kind="svc")
        x += bw + gap
    # shared schema
    s.box(360, 400, 560, 90, "shared PostgreSQL schema (public)", ["every context reads & writes here",
          "foreign keys cross context boundaries"], kind="data")
    # fan-in arrows from a few contexts
    for cx in (150, 510, 870, 1140):
        s.arrow(cx, 214, 640, 400, kind="muted", dashed=True)
    # smells callout
    s.panel(960, 150, 280, 64)
    s.label(976, 176, "planted smells", size=11, weight="bold", color=PALETTE["danger"])
    s.label(976, 196, "shared tables · in-process calls", size=11, color=PALETTE["muted"])
    s.write()


# ---------------------------------------------------------------------------
# 4 — event storming to seams
# ---------------------------------------------------------------------------
def r04_event_storm_seams():
    s = Scene("r04-event-storm-seams", 1240, 620,
              title="Finding the seams: from the event storm to bounded contexts",
              subtitle="Cluster the domain events by aggregate — the clusters are the seams, discovered, not invented")
    # left: the storm wall of events
    s.panel(40, 110, 470, 460)
    s.label(60, 136, "the storm wall — domain events", size=13, weight="bold", color=PALETTE["neutral"])
    events = ["OrderPlaced", "StockReserved", "PaymentCaptured",
              "ShipmentDispatched", "CustomerNotified", "ReviewSubmitted"]
    for i, e in enumerate(events):
        s.chip(70 + (i % 2) * 220, 175 + (i // 2) * 60, e, kind="govern", w=200)
    # arrow
    s.arrow(520, 340, 650, 340, kind="rest", label="cluster by aggregate")
    # right: six contexts
    s.panel(670, 110, 530, 460)
    s.label(690, 136, "bounded contexts = the six seams", size=13, weight="bold", color=PALETTE["neutral"])
    ctxs = ["order", "inventory", "payment", "shipping", "notification", "review"]
    for i, c in enumerate(ctxs):
        s.box(690 + (i % 2) * 265, 170 + (i // 3) * 0 + (i // 2) * 128, 245, 100, c,
              ["owns its data +", "its lifecycle"], kind="svc")
    s.write()


# ---------------------------------------------------------------------------
# 5 — strangler proxy + the six-extraction sequence
# ---------------------------------------------------------------------------
def r05_strangler_proxy():
    s = Scene("r05-strangler-proxy", 1240, 640,
              title="The strangler fig: a proxy in front, one seam out at a time",
              subtitle="A Camel proxy routes each path to the monolith or the extracted service — flip the flag when the suite stays green")
    s.box(40, 180, 170, 80, "client", ["same API"], kind="svc")
    s.box(300, 180, 210, 90, "strangler proxy", ["Camel .choice()", "routes by path + flag"], kind="rest")
    s.arrow(210, 220, 300, 220, kind="neutral")
    # two targets
    s.box(620, 90, 240, 80, "monolith", ["flag OFF -> old module"], kind="neutral")
    s.box(620, 280, 240, 90, "extracted service", ["flag ON -> Quarkus", "owns its data"], kind="platform")
    s.arrow(510, 200, 620, 130, kind="muted", dashed=True, label="flag off")
    s.arrow(510, 240, 620, 320, kind="rest", label="flag on")
    # sequence ladder
    s.panel(40, 430, 1160, 150)
    s.label(60, 456, "the six extractions, in dependency order", size=13, weight="bold", color=PALETTE["neutral"])
    seq = ["review", "notification", "inventory", "payment", "shipping", "order"]
    x = 70
    for i, c in enumerate(seq):
        s.chip(x, 490, f"{i+1}. {c}", kind="svc", w=170)
        if i < 5:
            s.arrow(x + 170, 500, x + 184, 500, kind="neutral")
        x += 184
    s.label(60, 560, "each: lift via Spring-compat -> idiomatic Quarkus -> own data -> cut over -> decommission", size=11, color=PALETTE["muted"])
    s.write()


# ---------------------------------------------------------------------------
# 6 — the transactional outbox
# ---------------------------------------------------------------------------
def r06_outbox_pipeline():
    s = Scene("r06-outbox-pipeline", 1260, 600,
              title="Data across the seam: the transactional outbox",
              subtitle="Write the business row and the event in ONE local transaction — then relay it; never dual-write")
    y = 200
    s.box(40, y, 180, 90, "service", ["handles the", "command"], kind="svc")
    s.box(280, y, 240, 100, "one DB transaction", ["business table +", "outbox table (atomic)"], kind="data")
    s.box(580, y, 180, 90, "relay", ["@Scheduled", "poll outbox"], kind="platform")
    s.box(820, y, 180, 90, "Kafka topic", ["order.placed", "payment.captured"], kind="platform")
    s.box(1060, y, 160, 90, "consumer", ["idempotent", "handler"], kind="svc")
    s.arrow(220, y + 45, 280, y + 45, kind="neutral")
    s.arrow(520, y + 45, 580, y + 45, kind="neutral")
    s.arrow(760, y + 45, 820, y + 45, kind="neutral")
    s.arrow(1000, y + 45, 1060, y + 45, kind="neutral")
    # anti-pattern callout
    s.panel(280, y + 150, 700, 70)
    s.label(300, y + 178, "WHY NOT DUAL-WRITE?", size=11, weight="bold", color=PALETTE["danger"])
    s.label(300, y + 200, "write-DB-then-publish crashes between the two steps -> the event is lost. The outbox makes it one atomic write.", size=12, color=PALETTE["muted"])
    s.write()


# ---------------------------------------------------------------------------
# 7 — choreographed vs orchestrated saga
# ---------------------------------------------------------------------------
def r07_choreo_vs_orchestr():
    s = Scene("r07-choreo-vs-orchestr", 1260, 640,
              title="Coordinating across services: two saga styles, same checkout",
              subtitle="Payment extraction is choreographed; shipping extraction is orchestrated with the Camel Saga EIP")
    # left: choreography
    s.panel(40, 110, 560, 480)
    s.label(60, 136, "Choreographed (payment) — no conductor", size=13, weight="bold", color=PALETTE["neutral"])
    s.box(220, 180, 200, 70, "order", kind="svc")
    s.box(220, 310, 200, 70, "payment", kind="svc")
    s.box(220, 440, 200, 70, "shipping", kind="svc")
    s.arrow(320, 250, 320, 310, kind="data", label="order.placed")
    s.arrow(320, 380, 320, 440, kind="data", label="payment.captured")
    s.label(70, 560, "services react to each other's events", size=11, color=PALETTE["muted"])
    # right: orchestration
    s.panel(640, 110, 580, 480)
    s.label(660, 136, "Orchestrated (shipping) — a route conducts", size=13, weight="bold", color=PALETTE["neutral"])
    s.box(830, 180, 210, 80, "saga orchestrator", ["Camel Saga EIP"], kind="govern")
    s.box(680, 350, 160, 70, "reserve", kind="svc")
    s.box(870, 350, 160, 70, "dispatch", kind="svc")
    s.box(1050, 350, 150, 70, "compensate", kind="danger")
    s.arrow(900, 260, 760, 350, kind="neutral")
    s.arrow(935, 260, 950, 350, kind="neutral")
    s.arrow(980, 260, 1120, 350, kind="neutral", label="on failure")
    s.label(660, 560, "one place sequences the steps + their compensations", size=11, color=PALETTE["muted"])
    s.write()


# ---------------------------------------------------------------------------
# 8 — the Quarkus / MicroProfile chassis
# ---------------------------------------------------------------------------
def r08_chassis():
    s = Scene("r08-chassis", 1240, 640,
              title="The chassis in 2026: a platform you opt into",
              subtitle="With Quarkus + MicroProfile the cross-cutting foundation is a dependency you declare, not a framework you build")
    cx, cy = 620, 340
    s.box(cx - 150, cy - 55, 300, 110, "your service", ["business logic", "— and little else"], kind="rest")
    sats = [
        ("config", "@ConfigProperty", 230, 150),
        ("health", "smallrye-health", 1010, 150),
        ("metrics", "micrometer", 150, 340),
        ("tracing", "opentelemetry", 1090, 340),
        ("native", "GraalVM image", 230, 530),
        ("dev services", "zero-config deps", 1010, 530),
    ]
    for t, sub, x, y in sats:
        s.box(x - 110, y - 40, 220, 80, t, [sub], kind="platform")
        s.arrow(x, y + (40 if y < cy else -40), cx, cy + (-55 if y < cy else 55), kind="muted", dashed=True)
    s.write()


# ---------------------------------------------------------------------------
# 9 — the final topology on Kubernetes
# ---------------------------------------------------------------------------
def r09_final_topology():
    s = Scene("r09-final-topology", 1280, 640,
              title="The end state: six services + a gateway, on Kubernetes",
              subtitle="The monolith is decommissioned; the proxy is now the permanent edge router; each service owns its schema")
    s.box(40, 150, 150, 70, "client", kind="svc")
    s.box(230, 150, 200, 80, "strangler proxy", ["NodePort edge"], kind="rest")
    s.arrow(190, 185, 230, 185, kind="neutral")
    # service row
    svcs = ["order", "inventory", "payment", "shipping", "notification", "review", "graphql-gw"]
    bw, gap = 150, 12
    x = 60
    for c in svcs:
        s.box(x, 300, bw, 64, c, kind="svc")
        x += bw + gap
    s.arrow(330, 230, 330, 300, kind="neutral", label="routes by path")
    # infra
    s.box(300, 460, 300, 80, "PostgreSQL", ["one schema per service"], kind="data")
    s.box(700, 460, 300, 80, "Kafka (KRaft)", ["outbox relays + sagas"], kind="platform")
    for sx in (135, 435, 735, 1035):
        s.arrow(sx, 364, 450, 460, kind="muted", dashed=True)
    for sx in (285, 585, 885):
        s.arrow(sx, 364, 850, 460, kind="muted", dashed=True)
    s.label(60, 580, "Istio mesh (mTLS + tracing) + LGTM observability layered on top — verified live on minikube", size=11, color=PALETTE["muted"])
    s.write()


# ---------------------------------------------------------------------------
# 10 — the supply-chain gate, before vs after
# ---------------------------------------------------------------------------
def r09_supply_chain_gate():
    s = Scene("r09-supply-chain-gate", 1260, 600,
              title="The supply-chain gate: the modernization, made measurable",
              subtitle="Same gate, same day: the monolith fails it, the extracted service passes it (syft + grype + policy-as-code)")
    # inputs
    s.box(40, 150, 240, 90, "monolith.jar", ["before — Spring Boot", "fat jar"], kind="neutral")
    s.box(40, 380, 240, 90, "order-service", ["after — Quarkus", "runtime closure"], kind="svc")
    # gate pipeline
    s.panel(360, 130, 470, 360)
    s.label(380, 156, "the gate — local and in CI", size=13, weight="bold", color=PALETTE["neutral"])
    s.box(400, 190, 390, 70, "1 - syft", ["CycloneDX SBOM"], kind="platform")
    s.box(400, 290, 390, 70, "2 - grype", ["scan vs CVE database"], kind="platform")
    s.box(400, 390, 390, 70, "3 - policy.yaml", ["fail-on: High"], kind="govern")
    s.arrow(280, 195, 400, 225, kind="neutral")
    s.arrow(280, 425, 400, 425, kind="neutral")
    s.arrow(595, 260, 595, 290, kind="neutral")
    s.arrow(595, 360, 595, 390, kind="neutral")
    # verdicts
    s.box(880, 150, 340, 110, "FAIL  — gate fires", ["4 Critical  6 High  8 Medium", "non-zero exit -> CI red"], kind="danger")
    s.box(880, 380, 340, 110, "PASS  — within policy", ["0 Critical  0 High  0 Medium", "exit 0 -> CI green"], kind="platform")
    s.arrow(790, 330, 880, 205, kind="danger", label="monolith")
    s.arrow(790, 460, 880, 435, kind="platform", label="order-service")
    s.write()


# ---------------------------------------------------------------------------
# 11 — the pattern scorecard
# ---------------------------------------------------------------------------
def r10_pattern_scorecard():
    s = Scene("r10-pattern-scorecard", 1280, 640,
              title="The pattern catalog, re-walked — a verdict per pattern",
              subtitle="Newman's incremental playbook, scored against the finished system; blue = used, amber = partial, grey = deferred")
    cols = [
        ("Decomposition", ["Strangler fig", "Bounded context", "Anti-corruption layer", "Rewrite (big-bang)"],
         ["svc", "svc", "svc", "muted"]),
        ("Data", ["Database per service", "Transactional outbox", "CDC (transition)", "Full event sourcing"],
         ["svc", "svc", "svc", "muted"]),
        ("Communication", ["Choreographed saga", "Orchestrated saga", "GraphQL / gRPC", "Schema registry"],
         ["svc", "svc", "svc", "govern"]),
        ("Reliability / Ops", ["Microservice chassis", "Mesh + tracing", "Supply-chain gate", "Circuit breaker"],
         ["svc", "svc", "svc", "muted"]),
    ]
    colw, x0, y0, bh, gap = 290, 40, 120, 86, 14
    for ci, (head, items, kinds) in enumerate(cols):
        x = x0 + ci * (colw + 15)
        s.label(x + colw / 2, y0 - 8, head, size=13, weight="bold", color=PALETTE["neutral"], anchor="middle")
        for ri, (it, k) in enumerate(zip(items, kinds)):
            y = y0 + 10 + ri * (bh + gap)
            tag = "deferred" if k == "muted" else ("partial" if k == "govern" else "used")
            s.box(x, y, colw, bh, it, [tag], kind=k)
    s.write()


def r_coupling_ladder():
    s = Scene("r-coupling-ladder", 1200, 880,
              title="The coupling ladder — sequencing the extractions",
              subtitle="Constantine's taxonomy, worst to best; five of seven rungs have a live example in the monolith")
    rows = [
        ("1. Content coupling (worst)", "placeOrder reads a live InventoryItem straight from inventory — Smell 5", "danger"),
        ("2. Common coupling", "one shared Postgres schema, six contexts, no enforced owner — Smell 1", "danger"),
        ("3. Control coupling", "paymentMethod() threaded into charge() to pick an approve / decline path", "govern"),
        ("4. Stamp coupling", "OrderItem takes the whole InventoryItem for the three fields it needs", "govern"),
        ("5. Data coupling — done right", "listAll() returns OrderDto via toDto(), not raw entities", "platform"),
        ("6. Message coupling (target)", "no monolith example — interaction only through messages (ch.14–26)", "muted"),
        ("7. API / data-structure (best)", "no monolith example — a versioned contract at the boundary", "muted"),
    ]
    x, w, y0, bh, gap = 150, 1010, 110, 92, 14
    for i, (title, detail, kind) in enumerate(rows):
        y = y0 + i * (bh + gap)
        s.box(x, y, w, bh, title, [detail], kind=kind)
    # worst -> best rail down the left
    y_top, y_bot = y0 + 20, y0 + 6 * (bh + gap) + bh - 20
    s.arrow(90, y_top, 90, y_bot, kind="neutral")
    s.label(70, y_top - 10, "worst", size=12, color=PALETTE["danger"], anchor="middle")
    s.label(70, y_bot + 22, "best", size=12, color=PALETTE["platform"], anchor="middle")
    s.label(150, y0 + 5 * (bh + gap) - 2, "no monolith example below rung 5 — the extractions build toward it",
            size=11, color=PALETTE["muted"])
    s.write()


def r_two_phase_migration():
    s = Scene("r-two-phase-migration", 1200, 740,
              title="The two-phase migration — the per-service recipe",
              subtitle="Phase A de-risks fast; Phase B makes the 'why Quarkus' case with numbers (Review service, measured)")
    s.box(70, 120, 320, 150, "Phase A — lift onto Quarkus",
          ["Spring-compat extensions", "spring-web / -di / -data-jpa", "suite green, fast"], kind="svc")
    s.box(440, 120, 320, 150, "Phase B — idiomatic Quarkus",
          ["Quarkus REST + Panache + CDI", "compat shim removed"], kind="platform")
    s.box(810, 120, 320, 150, "Phase B — native image",
          ["GraalVM Mandrel builder", "same code, no rewrite"], kind="rest")
    s.arrow(390, 195, 440, 195, kind="neutral", label="refactor")
    s.arrow(760, 195, 810, 195, kind="rest", label="package -Dnative")
    # measured metrics panel
    s.panel(70, 330, 1060, 320)
    s.label(100, 365, "Measured startup + RSS  (same Postgres, profile=prod)", size=14, weight="bold", color=PALETTE["neutral"])
    cols = [(110, "Build"), (640, "Startup"), (880, "RSS")]
    mrows = [
        ("Phase A — JVM (spring-compat)", "1.492 s", "~316 MB"),
        ("Phase B — JVM (idiomatic)", "1.43 s", "~304 MB"),
        ("Phase B — native image", "0.048 s", "~73 MB"),
    ]
    for cx, h in cols:
        s.label(cx, 405, h, size=12.5, weight="bold", color=PALETTE["neutral"])
    for i, (b, st, rss) in enumerate(mrows):
        y = 440 + i * 40
        kind = PALETTE["platform"] if i == 2 else PALETTE["neutral"]
        s.label(110, y, b, size=12.5, color=kind)
        s.label(640, y, st, size=12.5, color=kind)
        s.label(880, y, rss, size=12.5, color=kind)
    s.label(100, 620, "~30x faster startup, ~4.2x less resident memory, JVM to native — same idiomatic code.",
            size=12.5, weight="bold", color=PALETTE["platform"])
    s.write()


def r_context_map():
    s = Scene("r-context-map", 1200, 760,
              title="The context map — the six bounded contexts",
              subtitle="What the seam-finding turns into service boundaries; order holds four outbound edges, review none")
    order = (70, 300, 220, 110)
    s.box(*order, "order", ["checkout aggregate", "four outbound edges"], kind="rest")
    peers = [
        ("inventory", 440, 120, "stock per SKU"),
        ("payment", 440, 250, "charge capture"),
        ("shipping", 440, 380, "dispatch"),
        ("notification", 440, 510, "confirmation"),
    ]
    pboxes = {}
    for name, x, y, tag in peers:
        s.box(x, y, 240, 90, name, [tag], kind="svc")
        pboxes[name] = (x, y)
    s.box(820, 120, 200, 90, "review", ["no outbound edges", "extracted first"], kind="muted")
    s.box(430, 640, 420, 90, "PostgreSQL", ["one shared schema — no owner (shared kernel)"], kind="data")
    ox, oy = order[0] + order[2], order[1] + order[3] / 2
    # order -> inventory : conformist, ACL missing
    s.arrow(ox, oy, 440, 165, kind="govern", label="conformist · ACL missing")
    # order -> payment / shipping / notification : direct in-process calls
    s.arrow(ox, oy, 440, 295, kind="neutral", label="direct @Transactional call")
    s.arrow(ox, oy, 440, 425, kind="neutral")
    s.arrow(ox, oy, 440, 555, kind="neutral")
    # shared kernel
    s.arrow(order[0] + order[2] / 2, order[1] + order[3], 560, 640, kind="data", dashed=True)
    s.label(640, 620, "all six contexts read and write the same schema", size=11, color=PALETTE["muted"], anchor="middle")
    s.write()


def r_chassis_scorecard():
    s = Scene("r-chassis-scorecard", 1200, 900,
              title="The chassis scorecard — capabilities by status",
              subtitle="Verified against this repo's code; blue = used, grey outline = partial, light grey = deferred")
    rows = [
        ("1. Config — USED", "@ConfigProperty + %dev/%prod/%test profiles", "svc"),
        ("2. Fault Tolerance — DEFERRED", "no @Retry/@Timeout/@CircuitBreaker — manual deadlines stand in", "muted"),
        ("3. Health — PARTIAL", "smallrye-health on 6 of 8 (auto /q/health); no custom checks", "neutral"),
        ("4. Metrics — DEFERRED", "no Micrometer, no /q/metrics", "muted"),
        ("5. OpenAPI — DEFERRED", "no smallrye-openapi; the gateway's GraphQL schema replaces it", "muted"),
        ("6. REST Client — USED", "@RegisterRestClient in shipping + gateway resolvers, config-driven", "svc"),
        ("7. Security / JWT — PARTIAL", "HTTP Basic + @RolesAllowed on review; OIDC deferred", "neutral"),
        ("8. Native / build-time — USED", "native profile in all 8 poms — ~31x startup / ~4x memory", "svc"),
        ("9. Dev-mode — USED", "quarkus:dev live reload; Dev Services off (shared Postgres)", "svc"),
    ]
    x, w, y0, bh, gap = 70, 1060, 110, 72, 8
    for i, (title, detail, kind) in enumerate(rows):
        y = y0 + i * (bh + gap)
        s.box(x, y, w, bh, title, [detail], kind=kind)
    ly = y0 + 9 * (bh + gap) + 10
    legend = [("USED", "svc"), ("PARTIAL", "neutral"), ("DEFERRED", "muted")]
    for i, (lab, k) in enumerate(legend):
        s.box(70 + i * 360, ly, 330, 50, lab, [], kind=k)
    s.write()


# ---------------------------------------------------------------------------
# Quarkus & Camel deck — three new scenes
# ---------------------------------------------------------------------------
def r_quarkus_camel_map():
    """Where each runtime lives: Camel is the integration layer, Quarkus runs the services."""
    s = Scene("r-quarkus-camel-map", 1240, 640,
              title="Two runtimes, one system",
              subtitle="Apache Camel is the integration layer; Quarkus runs every service — and Camel runs on Quarkus")
    # Camel band (top)
    s.panel(40, 95, 1160, 150)
    s.label(60, 120, "Apache Camel — the integration layer (camel-quarkus, build-time, native-ready)",
            size=13, weight="bold", color=PALETTE["platform"])
    s.box(70, 150, 520, 74, "Edge router", ["content-based routing by URI path", "platform-http → the six services"], kind="platform")
    s.box(650, 150, 520, 74, "Saga orchestrator", ["Camel Saga EIP + compensation", "sequences the shipping fulfilment"], kind="platform")
    # Quarkus band (bottom)
    s.panel(40, 340, 1160, 250)
    s.label(60, 365, "Quarkus — the services (one runtime, opt-in extensions)",
            size=13, weight="bold", color=PALETTE["svc"])
    svcs = ["order", "inventory", "payment", "shipping", "notification", "review", "graphql-gateway"]
    bw, bh, gap, x, y = 150, 70, 14, 60, 400
    for i, c in enumerate(svcs):
        col, row = i % 4, i // 4
        s.box(x + col * (bw + gap), y + row * (bh + 20), bw, bh, c, kind="svc")
    # edge router -> services (REST); saga -> shipping (direct: steps)
    s.arrow(330, 224, 330, 400, kind="neutral", label="REST")
    s.arrow(910, 224, 627, 400, kind="muted", label="direct: steps")
    s.label(620, 612, "Camel-on-Quarkus: routes are built at image build time and run in the same fast, native-ready process as the services.",
            size=11, color=PALETTE["muted"], anchor="middle")
    s.write()


def r_quarkus_dev_loop():
    """Dev Services + continuous testing + live reload — the inner dev loop."""
    s = Scene("r-quarkus-dev-loop", 1200, 600,
              title="The inner dev loop",
              subtitle="mvn quarkus:dev — live reload, continuous testing, and Dev Services with no infrastructure to start by hand")
    s.box(460, 250, 280, 96, "mvn quarkus:dev", ["one command", "nothing else to start"], kind="rest")
    s.box(80, 110, 300, 84, "Edit code", ["save a .java / .properties"], kind="svc")
    s.box(820, 110, 300, 84, "Live reload", ["next request recompiles", "sub-second, no restart"], kind="platform")
    s.box(820, 400, 300, 84, "Continuous testing", ["affected tests re-run on save", "press 'r' to resume"], kind="platform")
    s.box(80, 400, 300, 84, "Dev Services", ["Postgres + Kafka auto-start", "as throwaway containers"], kind="data")
    s.arrow(380, 152, 460, 270, kind="neutral")
    s.arrow(740, 270, 820, 152, kind="neutral")
    s.arrow(740, 326, 820, 442, kind="neutral")
    s.arrow(380, 442, 460, 326, kind="muted", label="wired automatically")
    s.write()


def r_build_time_vs_runtime():
    """Quarkus's core idea: do the framework work at build time, not every boot."""
    s = Scene("r-build-time-vs-runtime", 1200, 600,
              title="Build-time, not boot-time",
              subtitle="Quarkus does classpath scanning, metamodel building, and proxying once at build — the runtime just runs")
    # traditional column
    s.panel(50, 110, 520, 420)
    s.label(74, 136, "Traditional JVM framework — at every boot", size=13, weight="bold", color=PALETTE["danger"])
    trad = [
        ("Scan the classpath", ["reflection over annotations"]),
        ("Build the metamodel", ["wiring, ORM, config"]),
        ("Generate proxies", ["dynamic, at startup"]),
        ("→ slow start, big heap", ["work repeated every launch"]),
    ]
    for i, (t, ln) in enumerate(trad):
        s.box(80, 170 + i * 88, 460, 70, t, ln, kind=("danger" if i == 3 else "muted"))
    # quarkus column
    s.panel(630, 110, 520, 420)
    s.label(654, 136, "Quarkus — once, at build time", size=13, weight="bold", color=PALETTE["platform"])
    q = [
        ("Augmentation at build", ["scan + wire + proxy ONCE"]),
        ("Closed-world metamodel", ["baked into the artifact"]),
        ("Tiny runtime init", ["no startup reflection"]),
        ("→ fast start, native-ready", ["Mandrel: ~0.05 s, ~73 MB"]),
    ]
    for i, (t, ln) in enumerate(q):
        s.box(660, 170 + i * 88, 460, 70, t, ln, kind=("platform" if i == 3 else "svc"))
    s.arrow(540, 400, 660, 400, kind="neutral", label="moved left")
    s.write()


SCENES = [
    r07_choreo_vs_orchestr,
    r08_chassis,
    r09_final_topology,
    r_two_phase_migration,
    r_quarkus_camel_map,
    r_quarkus_dev_loop,
    r_build_time_vs_runtime,
]


if __name__ == "__main__":
    for fn in SCENES:
        fn()
        print(f"  built {fn.__name__}")
