#!/usr/bin/env python3
"""ch.03 figure: migration-metrics-review — the Review-extraction measurement
table turned into a comparison figure: three builds (Phase A JVM Spring-compat,
Phase B JVM idiomatic Quarkus, Phase B native image) across two metrics
(startup time, resident memory), bars sized to the measured values so the
native column's startup and memory wins are visible at a glance rather than
only readable from a table.

This is a box/band/edge generator, not a charting library (project decision
D3) — the "bars" below are plain node() rectangles whose WIDTH is scaled to
the measured value; the number itself is drawn as a separate text label next
to the bar rather than inside it, because the native-image startup bar is
intentionally too narrow to hold text (that narrowness IS the point: it is
~31x shorter than Phase A's bar). Each band uses its own width scale (anchored
so Phase A's bar is the longest in that band); the two bands are not on a
shared scale with each other — seconds and megabytes are not comparable
quantities, only the three bars within one band are.

Exact numbers are the ones in the chapter's own measured table in
_docs/03-modernization-as-engineering.md (sourced from
examples/02-review-service/MIGRATION.md): Phase A 1.492 s / ~316 MB; Phase B
JVM 1.431–1.437 s / ~304 MB; Phase B native 0.048–0.049 s / ~73 MB. All three
builds pass the same 16/16-assertion equivalence suite unchanged — the point
of the whole exercise is that the suite is what makes the speed/memory
comparison trustworthy in the first place, not just a chart someone drew.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node

g.OUT = os.path.dirname(__file__)

W, H = 1500, 740

LABEL_X = 40
BAR_X = 300
BAR_MAX = 860          # Phase A's bar length in px, in both bands (own scale per band)
BAR_H = 46
ROW_GAP = 16
ROW_PITCH = BAR_H + ROW_GAP
EQUIV_X = 1280

builds = [
    # (label, style)
    ("Phase A — JVM, Spring-compat", "box"),
    ("Phase B — JVM, idiomatic Quarkus", "accent"),
    ("Phase B — native image", "ink"),
]

startup_vals = [1.492, 1.434, 0.0485]          # midpoints used only to size bars
startup_labels = ["1.492 s", "1.431–1.437 s", "0.048–0.049 s"]

memory_vals = [316, 304, 73]
memory_labels = ["~316 MB", "~304 MB", "~73 MB"]


def bars_for_band(band_y, vals, value_labels, header_h=34):
    scale = BAR_MAX / vals[0]
    nodes, notes = [], []
    row_top = band_y + header_h
    for i, ((label, style), val, vlabel) in enumerate(zip(builds, vals, value_labels)):
        top = row_top + i * ROW_PITCH
        w = max(6, val * scale)
        nodes.append(node(BAR_X, top, w, BAR_H, [""], style=style))
        ty = top + BAR_H / 2 + 5
        notes.append({"x": LABEL_X, "y": ty, "text": label, "anchor": "start", "size": 12.5, "bold": True})
        notes.append({"x": BAR_X + w + 14, "y": ty, "text": vlabel, "anchor": "start", "size": 12.5, "color": "#1d1d1d"})
        notes.append({"x": EQUIV_X, "y": ty, "text": "16/16 green", "anchor": "start", "size": 11.5, "color": "#2f5f3d"})
    last_bottom = row_top + 3 * ROW_PITCH - ROW_GAP
    return nodes, notes, last_bottom


band1_y = 90
band1_h = 250
band2_y = band1_y + band1_h + 40
band2_h = 250

band_startup = {"x": 20, "y": band1_y, "w": 1460, "h": band1_h,
                "label": "Startup time — lower is better (own scale; Phase A's bar is the longest in this band)",
                "fill": "#fafafa"}
band_memory = {"x": 20, "y": band2_y, "w": 1460, "h": band2_h,
               "label": "Resident memory — lower is better (own scale; Phase A's bar is the longest in this band)",
               "fill": "#eaf4ec"}

nodes1, notes1, bottom1 = bars_for_band(band1_y, startup_vals, startup_labels)
nodes2, notes2, bottom2 = bars_for_band(band2_y, memory_vals, memory_labels)

callout1 = {"x": LABEL_X, "y": bottom1 + 30, "text": "~31x faster: 1.492 s -> 0.048–0.049 s (Phase A to native)",
            "anchor": "start", "bold": True, "size": 12.5, "color": "#2f5f3d"}
callout2 = {"x": LABEL_X, "y": bottom2 + 30, "text": "~4x smaller: ~316 MB -> ~73 MB (Phase A to native)",
            "anchor": "start", "bold": True, "size": 12.5, "color": "#2f5f3d"}

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.03 — the Review-extraction measurement: Phase A -> Phase B JVM -> Phase B native",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "the same 16/16-assertion equivalence suite passed, unchanged, on all three builds",
     "anchor": "middle", "size": 12, "color": "#2f5f3d"},
]

notes += notes1 + [callout1] + notes2 + [callout2]

notes.append({
    "x": LABEL_X, "y": H - 28,
    "text": "Sourced from _docs/03-modernization-as-engineering.md's measured metrics table and "
            "examples/02-review-service/MIGRATION.md. Bar length is proportional to the value within "
            "its own band only — seconds and megabytes are not on a shared scale across the two bands.",
    "anchor": "start", "size": 10, "color": "#777777",
})

g.emit(
    "migration-metrics-review", W, H,
    bands=[band_startup, band_memory],
    nodes=nodes1 + nodes2,
    edges=[],
    notes=notes,
)
