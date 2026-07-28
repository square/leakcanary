# Treemap rendering

Implemented in `shark-explorer-core`: `Squarify.kt` (row layout), `TreemapLayout.kt` (adaptive depth
and hit testing), `TreemapRect.kt`. Not yet wired to a dominator tree or to the UI.

## Depth is area-driven, not a fixed level

A heap dump's dominator tree has ~1 M nodes; a treemap can usefully show a few thousand rectangles.
Picking a fixed depth is the wrong knob — the same depth is far too coarse for the one huge node and
absurdly fine for the long tail.

The model instead: lay out one level, then recurse into a child's rectangle only if it's big enough
to be worth subdividing, and stop when a rectangle would be too small to see.

- Subdivide only above roughly **40×24 dp** — enough for a header strip plus one visible child.
- Don't draw below roughly **3×3 px**; it's invisible and it costs draw calls.
- Cap the total rectangle count (order of **5000**) and spend that budget largest-rectangle-first, so
  detail lands where there's space for it.

Depth then varies across the treemap, which is the point. It's also deterministic, so the budget and
recursion are directly unit-testable.

A node is subdivided either fully or not at all. Laying out only some of a node's children reads as
if the rest of it were empty, which is worse than showing it as one rectangle. Nodes skipped for
budget reasons are counted in `TreemapLayoutResult.truncatedNodeCount` rather than silently dropped.

Note that a node whose share of the area makes it thinner than the minimum drawable size disappears
entirely rather than becoming a sliver. Past roughly a 50:1 weight ratio between siblings, the
smaller one isn't drawn at all — expected, but surprising when writing tests against it.

Clicking a node re-roots the treemap at it and re-runs the same layout against the full viewport, so
zooming is how deeper detail is reached. Breadcrumbs walk back up.

This is what the existing Android implementation's TODO was asking for: *"Ideally depth & min size
would be handled dynamically by the layout algo based on available space."* It currently hardcodes
`maxDepth = 1, minSize = 10000`.

## Bugs in the existing Android treemap

`leakcanary-app`'s `TreemapLayout` is a d3-hierarchy squarify port and is the obvious starting point,
but two things are wrong with it and must not be carried over:

1. **Int overflow in `squarifyRatio`.** `beta = sumValue * sumValue * alpha` with `sumValue: Int` is
   an `Int` multiply, so it overflows above ~46 341. Retained sizes in bytes are far past that, which
   means every aspect-ratio decision on real heap data is currently garbage. Almost certainly the
   cause of the neighbouring `// TODO Figure out what's up with negative numbers` comment, and of the
   `// TODO Float` on `NodeValue.value`. Use `Double` for the ratio math and `Long` for sizes.
2. **The node tree is built eagerly and recursively** before layout runs. Fine for a four-node
   preview, will exhaust memory or the stack on a real dominator tree. Layout has to descend lazily,
   which the area-driven model above needs anyway.

## Hit testing

Rectangles are drawn into a single `Canvas`, so Compose has no per-rectangle node to hit test or to
expose to tests. Hit testing is therefore explicit: keep the laid-out rectangles and resolve a click
by point containment, deepest match wins.

Keeping that a pure function in `shark-explorer-core` is what makes it testable — see
`decisions.md` for how the UI tests are structured around this.
