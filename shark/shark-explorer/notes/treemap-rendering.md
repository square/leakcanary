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

- Subdivide only above roughly **12×12 dp**.
- Don't draw below roughly **3×3 dp**; it's invisible and it costs draw calls.
- Cap the total rectangle count (order of **5000**) and spend that budget largest-rectangle-first, so
  detail lands where there's space for it.

`TreemapLayout` works in pixels, so `TreemapView` scales those thresholds by the current density.
Passing them straight through would make every rectangle half its intended size on a 2x display.

Depth then varies across the treemap, which is the point. It's also deterministic, so the budget and
recursion are directly unit-testable.

### A level costs no area, because a label strip is the whole viewport

The first version reserved an **18 dp header** at the top of every subdivided rectangle for its label,
and `minSubdivideHeight` was 24 dp because a level had to fit a header plus one visible child. On a real
app that hides everything worth seeing: the chain from the activity down to a list row in the 82 MB
production dump was **38 levels**, and 38 × 18 dp is 684 dp of a 630 dp viewport. The window filled up
with full-width label bands and the bitmaps at the bottom of the chain never got drawn at all. Drilling
in only bought back 18 dp per level skipped, so it took several goes and still ran out.

So a subdivided node's children now cover it **exactly**, and nesting is drawn afterwards instead of
being given room: `TreemapView` draws every fill first and every outline second, so a level reads as a
1 px line over its contents rather than as a strip beside them. Where a chain of single children shares
an edge the outlines stack up into a heavier line, which is the view saying there's more here than one
rectangle. Measured on that dump in a 1180×630 px viewport: the three biggest bitmaps come out at
**depth 22, 128×75 px, 1.3% of the view each**, 2,279 cells, nothing truncated. (That chain is 22 levels
rather than 38 because the `View[]` between every two views is no longer a level of the tree — see
`dominator-tree.md`. The arithmetic above is what it was when it was 38, and it's still 378 dp of a 630 dp
viewport at 21 headers, so the conclusion doesn't move.)

Two things follow, and both are behaviour rather than polish:

- **A node's own weight gets a cell.** `squarify()` normalizes, so children fill whatever they're given:
  without a `CellSubject.Own` cell of `weight − Σ children`, they'd be scaled up to fill their parent and
  area would only be proportional to weight *among siblings*. With it, a rectangle is its share of the
  whole heap at every depth, which is what makes a big object findable without knowing where to look.
  (On the production dump only 4 own cells survive the 3×3 dp floor — an object's own bytes are usually a
  rounding error next to what it retains. The ones that don't survive are exactly the ones you don't need
  to see, and the one that does is a bitmap.)
- **A container is pressed by its outline.** Its children cover every pixel of it, so `cellAt` takes an
  `edgeGrab` — 4 dp — within which a subdivided cell wins over whatever shares that edge. Without it
  there is no way to point at a container at all. Pointing at a *gap* in a subdivision, area left by
  children too small to draw, still lands on the node holding it.

And one consequence for the UI: a subdivided rectangle has nowhere to put its own name, so only cells
with nothing drawn inside them are labelled, and naming the levels is the view's job — `TreemapView`
shows the chain of containers under the pointer as one line at the bottom of the view.

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
together, so that a child is never silently dropped from the area its parent hands out. A `Group` is a
rectangle, not a tree node: it can't be subdivided or zoomed into, and clicking it says how many
objects it stands for. Same dump, same viewport, after the change: 1,190 cells, 28 groups, 7 levels
deep, nothing truncated.

A radial layout has one more bound: **rings**. Eight around the centre disk, the width of each derived
from the viewport, so the picture always fills the circle and depth past that needs a zoom. Sectors
take their parent's whole sweep divided by weight, and a ring band is where a node's own name fits, so
the radial view has not needed the treemap's own-weight cell.

Two consequences worth knowing before writing a test against the layout:

- **`squarify()` needs descending weights, and neither of the two synthetic cells is in weight order.**
  A group stands for many children so it usually outweighs the smallest ones drawn individually, and a
  node's own weight lands anywhere. `TreemapLayout` builds the cells and sorts them by weight, which is
  why they aren't taken straight from the children — and it's a stable sort, so layout stays
  deterministic.
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

## A bitmap's rectangle shows the bitmap

Where the pixels come from is `bitmaps.md`; what the view does with them is three things.

**They're drawn between the fills and the outlines.** A bitmap's own pixels are the child rectangle
covering it — its `byte[]` before API 26, nothing at all after — so an image drawn with the fills would
be painted over by that child, and drawn with the outlines it would cover the nesting it sits in. Order
is therefore: every fill, every image, every outline and label, then the selection.

**An image is fitted, never stretched.** A rectangle's aspect ratio is its share of the heap, which has
nothing to do with the bitmap's, and an icon squashed into it isn't recognisable. So `imageBounds`
centres the biggest fit and the rest of the rectangle stays its fill colour.

**A cell with an image gets no label**, since text over a picture reads as neither. The chain at the
bottom of the view already names what the pointer is on, which is where a bitmap's class and size are
read.

Two wiring details that are easy to get wrong: images are asked for per presentation, only for
rectangles at least `MIN_BITMAP_DRAW_SIZE` (8 dp) each way — below that an image is a smear of one
colour and it would still cost a heap dump read and a decode — and `bitmapImages` is a key of the
`remember` that measures the cells, because a label depends on whether there's an image.

## Hit testing

Cells are drawn into a single `Canvas`, so Compose has no per-cell node to hit test or to expose to
tests. Hit testing is therefore explicit: keep the laid out cells and resolve a click against their
geometry — deepest rectangle containing the point for a treemap, ring and angle for the radial view.

**Except that the deepest rectangle is not always the answer.** A subdivided rectangle is covered by its
own children, so `cellAt` takes an `edgeGrab`: within that distance of an edge, a container wins over
whatever shares it, which is the line the view draws there. A gap in a subdivision still belongs to the
node being subdivided. This is what a UI test presses to reach a container — `pressContainerEdge` — since
the label bands it used to press are gone.

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
