# Treemap rendering

Implemented in `shark-explorer-core`: `Squarify.kt` (row layout), `TreemapLayout.kt` (adaptive depth
and hit testing), `TreemapRect.kt`, `HeapTreemap.kt` (the dominator tree as a `TreemapTree`). Drawn
by `TreemapView` in `shark-explorer-app`.

## Depth is area-driven, not a fixed level

A heap dump's dominator tree has ~1 M nodes; a treemap can usefully show a few thousand rectangles.
Picking a fixed depth is the wrong knob — the same depth is far too coarse for the one huge node and
absurdly fine for the long tail.

The model instead: lay out one level, then recurse into a child's rectangle only if it's big enough
to be worth subdividing, and stop when a rectangle would be too small to see.

- Subdivide only above roughly **40×24 dp** — enough for a header strip plus one visible child.
- Don't draw below roughly **3×3 dp**; it's invisible and it costs draw calls.
- Cap the total rectangle count (order of **5000**) and spend that budget largest-rectangle-first, so
  detail lands where there's space for it.

`TreemapLayout` works in pixels, so `TreemapView` scales those thresholds by the current density.
Passing them straight through would make every rectangle half its intended size on a 2x display.

Depth then varies across the treemap, which is the point. It's also deterministic, so the budget and
recursion are directly unit-testable.

A node is subdivided either fully or not at all. Laying out only some of a node's children reads as
if the rest of it were empty, which is worse than showing it as one rectangle. Nodes skipped for
budget reasons are counted in `TreemapLayoutResult.truncatedNodeCount` rather than silently dropped.

Note that a node whose share of the area makes it thinner than the minimum drawable size disappears
entirely rather than becoming a sliver. Past roughly a 50:1 weight ratio between siblings, the
smaller one isn't drawn at all — expected, but surprising when writing tests against it.

Double clicking a node re-roots the treemap at it and re-runs the same layout against the full
viewport, so zooming is how deeper detail is reached. Breadcrumbs walk back up. A single press only
selects, and it's handled on press rather than on tap: `detectTapGestures` delays `onTap` by the
double click window when `onDoubleTap` is set, which makes selection feel stuck.

## Two bugs from the deleted Android treemap, for the record

`leakcanary-app` had a d3-hierarchy squarify port, removed in `aa2bc4240`. Two things were wrong with
it and must not come back:

1. **Int overflow in `squarifyRatio`.** `beta = sumValue * sumValue * alpha` with `sumValue: Int`
   overflows above ~46 341. Retained sizes in bytes are far past that, so every aspect ratio decision
   on real heap data was garbage — almost certainly the cause of its
   `// TODO Figure out what's up with negative numbers` comment. Ratio math is in `Double` here, and
   sizes in `Long`.
2. **The node tree was built eagerly and recursively** before layout ran. Fine for a four node
   preview, fatal on a real dominator tree. `TreemapTree` is read lazily instead, which the
   area-driven model needs anyway.

It also hardcoded `maxDepth = 1, minSize = 10000` under a TODO asking for exactly the adaptive model
above.

## Hit testing

Rectangles are drawn into a single `Canvas`, so Compose has no per-rectangle node to hit test or to
expose to tests. Hit testing is therefore explicit: keep the laid-out rectangles and resolve a click
by point containment, deepest match wins.

Keeping that a pure function in `shark-explorer-core` is what makes it testable — see
`decisions.md` for how the UI tests are structured around this.
