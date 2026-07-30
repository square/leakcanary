# Rendering the dominator tree

Implemented in `shark-explorer-core`: `Squarify.kt` (row layout), `TreemapLayout.kt` (adaptive depth
and hit testing), `RadialLayout.kt` (the same, as rings), `TreemapRect.kt`, `LayoutCell.kt` (what the
two layouts have in common), `HeapDominatorTreemap.kt` (the dominator tree as a `TreemapTree`, and
`present()` / `presentRadial()`, which turn a layout into the labelled, coloured presentation the UI
draws). Drawn by `TreemapView` and `RadialView` in `shark-explorer-app`.

## Two shapes, one cut of the tree

A cell is a `LayoutCell`: a `CellSubject` — one node, or the children a node didn't draw — plus a
depth, a weight and whatever geometry its layout adds. `TreemapCell` adds a rectangle, `RadialCell` an
annular sector. Everything downstream of the geometry works off `CellSubject` alone: labels, colours,
what a click selects, what a double click zooms into. That split is what keeps the second shape from
being a second copy of all of it, and it's why adding a third would mean one new layout plus one new
`Canvas`, nothing else.

The two layouts make the same decisions — largest cell subdivided first, children too small to see
grouped, a cell budget, truncation counted — differing only in what "too small" measures. A treemap
compares areas; the radial view compares arc lengths along the middle of a ring, because a sector of
the same sweep is bigger the further out it sits. A ring holds far less than a rectangle does: 50
equally sized children under one node is already past what a ring can show one by one, where a treemap
draws a couple of hundred. So the radial view groups sooner and is the better read of the *shape* of
the tree, while the treemap is the better read of sizes.

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
first model, and it made **the whole treemap a single rectangle on a real heap dump**. The GC roots of
`compose_leak.hprof` dominate **27,476 objects directly**, well past the 5,000 cell budget, so that cell
failed to subdivide and nothing below it was ever reached. (Grouping those by class, which came later,
brings them down to a few hundred cells — but only at the top of the tree, and the layout can't rely on
it.) Nothing is drawable and
nothing is zoomable, which reads as "everything is dominated by the root" rather than as a bug.

So a subdivision draws the children it can — largest first, up to `maxChildrenPerNode`, the area floor
and the remaining budget — and the rest become a single `CellSubject.Group`, weighing what they weigh
together. The area a parent hands out is then always the full share of its children, so space a node
keeps to itself means "this object's own bytes" and never "children I gave up on". A `Group` is a
rectangle, not a tree node: it can't be subdivided or zoomed into, and clicking it says how many
objects it stands for. Same dump, same viewport, after the change: 1,190 cells, 28 groups, 7 levels
deep, nothing truncated.

A radial layout has one more bound: **rings**. Eight around the centre disk, the width of each derived
from the viewport, so the picture always fills the circle and depth past that needs a zoom. Sectors
take their parent's whole sweep divided by weight, so the analogue of a treemap's header strip — the
space a node keeps for itself — is the node's own ring band.

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

## Colour: a vivid hue for what isn't strongly reachable, a scheme for the rest

Hue is what the eye picks out of a treemap, and the thing worth picking out is a weakly reachable
block sitting inside a strongly reachable one — so an object that isn't strongly reachable gets a vivid
hue of its own whatever the scheme. Nearly all of the heap is strongly reachable, and how *that* is
coloured is a scheme, picked above the view:

- **Daisy**, the default: one hue per top level block, inherited by everything nested in it and
  lightening with depth, the way DaisyDisk colours a disk. A block then reads as one thing with its
  contents. It needs to know which top level block a cell belongs to, which is what `parent` and
  `siblingIndex` on `CellSubject.Node` are for, resolved in one pass per presentation — cells come
  parent before child, so a parent always has its hue by the time a child is reached. "Top level" here
  means `TOP_LEVEL_DEPTH`, the children of the two halves of the heap dump, and not the halves: the tree
  has the GC roots and the garbage above everything, so handing hues out at the root would paint the
  whole view in one or two of them.
- **Reachability**: one hue per strength, shaded by depth. Says the most about the collector and the
  least about structure.
- **Slate**: blue greys only, for when the colours get in the way of the shapes.

Depth is unbounded, so shades cycle, which is fine as long as neighbours differ. A cell standing for many
objects rather than one — a class group, or the siblings that didn't fit — is a washed out version of its
strength, cool slate when that strength is `STRONG`, so it reads as "not an object" without needing a
colour of its own. All of it is in `CellColors.kt`, the one place the colours are named.

**Grey means one thing only: a strength switched off.** The checkboxes above the view are a `CellColoring`,
and unchecking one greys out everything held that firmly rather than hiding it — the tree is the whole heap
dump either way, so there is no strength it makes no sense to press, and toggling one is a repaint. Greying
the strong heap is what makes the little there is of everything else jump out. That's why nothing else in
any scheme is allowed to be grey, which is what the `CellColorsTest` cases about grey are pinning.
`UNREACHABLE` is shaded by depth like the strong heap, unlike the other non-strong strengths, because there
can be megabytes of uncollected garbage and one flat colour over all of it would hide its shape.

## Hit testing

Cells are drawn into a single `Canvas`, so Compose has no per-cell node to hit test or to expose to
tests. Hit testing is therefore explicit: keep the laid out cells and resolve a click against their
geometry — deepest rectangle containing the point for a treemap, ring and angle for the radial view.

The chain of dominators down to a cell comes from `nodePathTo`, which walks the `parent` on each
`CellSubject.Node` up to the root. A press selects the cell under the pointer; a double click zooms in
**along the whole chain**, so a cell five levels down leaves a breadcrumb per dominator rather than a
jump straight to it. A group isn't a node, so the path to one ends at the node whose children it stands
for: double clicking a group zooms into what holds it, which is the only way to see what's in it.

Keeping all of that pure functions in `shark-explorer-core` is what makes it testable — see
`decisions.md` for how the UI tests are structured around this.

Two things about selection that aren't obvious from the drawing code:

- **A selection is an id, not a cell.** Resizing the window or switching shape lays the view out again and
  every cell is a new object, so the UI remembers a `SelectedCell`: an object id, or a parent id plus a
  flag for the leftover cell. Which is the other
  reason `CellSubject.Group` carries its `parent` — with nothing to tell one group from another, every
  leftover rectangle in the treemap lit up at once.
- **The outline of the selected cell is drawn after every cell**, not with its own. Children are drawn
  over their parents, so a selected rectangle that has any would otherwise show only the slivers of its
  outline that its children don't cover.
