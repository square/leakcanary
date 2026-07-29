# Treemap rendering

Implemented in `shark-explorer-core`: `Squarify.kt` (row layout), `TreemapLayout.kt` (adaptive depth
and hit testing), `TreemapRect.kt`, `HeapDominatorTreemap.kt` (the dominator tree as a `TreemapTree`,
and `present()`, which turns a layout into the labelled, coloured `TreemapPresentation` the UI draws).
Drawn by `TreemapView` in `shark-explorer-app`.

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

### The children that don't fit become one rectangle, they aren't dropped

All-or-nothing subdivision — a node is either fully laid out or shown as a bare rectangle — was the
first model, and it made **the whole treemap a single rectangle on a real heap dump**. The root of
`compose_leak.hprof`'s dominator tree has **27,476 direct children**, well past the 5,000 cell budget,
so the root itself failed to subdivide and nothing below it was ever reached. Nothing is drawable and
nothing is zoomable, which reads as "everything is dominated by the root" rather than as a bug.

So a subdivision draws the children it can — largest first, up to `maxChildrenPerNode`, the area floor
and the remaining budget — and the rest become a single `TreemapCell.Group`, weighing what they weigh
together. The area a parent hands out is then always the full share of its children, so space a node
keeps to itself means "this object's own bytes" and never "children I gave up on". A `Group` is a
rectangle, not a tree node: it can't be subdivided or zoomed into, and clicking it says how many
objects it stands for. Same dump, same viewport, after the change: 1,190 cells, 28 groups, 7 levels
deep, nothing truncated.

Two consequences worth knowing before writing a test against the layout:

- **`squarify()` needs descending weights, and a group is not in weight order.** It stands for many
  children, so it usually outweighs the smallest ones drawn individually. `TreemapLayout` inserts it
  at the position its combined weight belongs at, which is why the cell weights are rebuilt rather
  than taken straight from the children.
- A node whose share of the area makes it thinner than the minimum drawable size disappears rather
  than becoming a sliver — including a group, when the tail it stands for is small enough. Past
  roughly a 50:1 weight ratio between siblings, the smaller one isn't drawn at all.

Nodes that had children but weren't subdivided at all are still counted in
`TreemapLayoutResult.truncatedNodeCount`, and the UI says so, so a treemap showing less detail than it
had room for is visible rather than silent.

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

## Colour says reachability, shade says depth

Hue is what the eye picks out of a treemap, and the thing worth picking out is a weakly reachable
block sitting inside a strongly reachable one — so hue carries the reachability strength and depth only
varies saturation and brightness. Depth is unbounded, so the shades cycle every five levels, which is
fine as long as neighbours differ. Groups are grey, so they read as "not an object". All of it is in
`ReachabilityColors.kt`, the one place the colours are named.

## Hit testing

Rectangles are drawn into a single `Canvas`, so Compose has no per-rectangle node to hit test or to
expose to tests. Hit testing is therefore explicit: keep the laid-out rectangles and resolve a click
by point containment, deepest match wins.

Keeping that a pure function in `shark-explorer-core` is what makes it testable — see
`decisions.md` for how the UI tests are structured around this.
