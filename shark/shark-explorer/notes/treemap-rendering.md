# Rendering the dominator tree

Implemented in `shark-explorer-core`: `Squarify.kt` (row layout), `TreemapLayout.kt` (adaptive depth
and hit testing), `RadialLayout.kt` (the same, as rings), `StackLayout.kt` (the same, as a row per
level), `TreemapRect.kt`, `LayoutCell.kt` (what the three layouts have in common),
`HeapDominatorTreemap.kt` (the dominator tree as a `TreemapTree`, and `present()`, which labels and
colours the cells a layout produced), `TreemapPresentation.kt` (a presentation per shape, each with an
`of()` pairing a layout with that). Drawn by `TreemapView`, `RadialView` and `StackView` in
`shark-explorer-app`.

**`of()` is a presentation's own, not a method per shape on `HeapDominatorTreemap`.** It used to be the
other way round, and adding a third shape is what moved it: that class is a 1,200 line heap dump reader
which detekt allows 50 functions, and `presentStack` was the fiftieth. Rather than split it somewhere
arbitrary, the per-shape method came out of it — which is also the better line, since which shapes exist
is no business of a heap dump reader. `present(cells)` is all that stayed behind: it reads a name and a
strength off a `CellSubject`, and every shape's cells are those. **So a fourth shape needs nothing in
`HeapDominatorTreemap` at all.**

## Three shapes, one cut of the tree

A cell is a `LayoutCell`: a `CellSubject` — one node, or the children a node didn't draw — plus a
depth, a weight and whatever geometry its layout adds. `TreemapCell` adds a rectangle, `RadialCell` an
annular sector, `StackCell` a rectangle again, on a row. Everything downstream of the geometry works off
`CellSubject` alone: labels, colours, where a click goes. That split is what keeps a second shape from
being a second copy of all of it, and the third shape is what confirmed the price: `StackLayout` plus
`StackView`, one new `Canvas`, and three small things beside them — a `ViewShape`, a `ViewPresentation`
and a `StackPresentation` with its `of()`. Nothing about colouring, labelling, selection, hit resolution
or navigation moved.

The three layouts make the same decisions — largest cell subdivided first, children too small to see
grouped, a cell budget, truncation counted — differing only in what "too small" measures. A treemap
compares areas; the radial view compares arc lengths along the middle of a ring, because a sector of
the same sweep is bigger the further out it sits; the stack compares widths, since a row's height is
fixed. A ring holds far less than a rectangle does: 50 equally sized children under one node is already
past what a ring can show one by one, where a treemap draws a couple of hundred. So the radial view
groups sooner and is the better read of the *shape* of the tree, while the treemap is the better read of
sizes.

## The stack: depth is a row, not an area

Profilers draw a call tree as an icicle chart — a row per level, roots at the top, a block's width its
share of the whole — and a dominator tree reads the same way, with "who retains this" in place of "who
called this". `StackLayout` is that chart.

What it buys over the other two is that **a level costs no width**. A treemap and a ring both pay area
for nesting, so the deep end of a chain is a sliver; a stack gives every level a full row, so the last
object in a 22-level chain is drawn as wide as its share of the heap deserves and — this is the part
that matters — **named**, at every depth, along with its size. The treemap can only name one level
(see below) because a subdivided rectangle is covered by its children. A row is covered by the row
below it, not by its own contents, so there is nothing in the way of a label.

Three consequences of that, each of them a decision:

- **Children are sized against the parent's own weight, and the remainder is a `CellSubject.Own`
  block** at the right end of the row — the same reason the treemap has one, for the same effect: a
  block is its share of the whole heap at every depth rather than of its siblings. It also means no
  pixel of a row belongs to nothing, so hit testing has no gaps to explain.
- **The stack has to bound its rows** (`maxRows`, 64). The cell budget doesn't bound it: a chain of
  single dominators never narrows, so every level of it clears the subdivide floor and 5,000 cells is a
  5,000-row canvas. The other two shapes are bounded by their own geometry — a ring's arc, a
  rectangle's area — and needed no such number.
- **It is the one shape taller than the window, so it scrolls.** Which puts the pointer and the blocks
  a scroll offset apart: `StackView` keeps the pointer where the pointer is, in the view's coordinates,
  and adds the scroll only when asking the layout what is under it. The hover has to be worked out again
  when the offset changes, too, since scrolling under a still pointer moves a different block under it
  without any pointer event to say so.

It skips one thing the treemap does: **a row doesn't draw its bitmap**. A row is a line of text tall,
and a bitmap fitted into 18 dp is a smear — the picture is the treemap's contribution, and asking for it
here would cost a heap dump read and a decode per row for nothing.

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
- **A container is pressed by its name, or by its outline.** Its children cover every pixel of it, so
  `cellAt` takes an `edgeGrab` — 4 dp — within which a subdivided cell wins over whatever shares that
  edge, and the view checks the plate under a rectangle's name before that. Without either there is no
  way to point at a container at all. Pointing at a *gap* in a subdivision, area left by children too
  small to draw, still lands on the node holding it.

And one consequence for the UI: a subdivided rectangle has nowhere to put its own name, so naming the
levels falls to what is drawn beside the view — `RootPathPanel`, which draws the chain from the whole heap
dump down to the object clicked, runs it on to the object under the pointer, and marks the steps that
dominate it. Those marked steps are the containers the rectangle sits inside, so the same pane answers both
"what is this" and "where in the picture am I".
See `decisions.md`.

### One level is named, and its boundaries are the heavy lines

Only the **current root's own children** — `ROOT_CHILD_DEPTH`, depth 1 of the presentation — carry a label,
and every one of them with the room for it does, contents and bitmaps included. Labelling every leaf instead
is what made a real dump's map unreadable: a hundred class names across half a dozen levels, each naming
something the level below it covers, and none of them the level being read.

Two things make that one level legible:

- **The label goes over what's nested inside it**, on a translucent plate (`LABEL_PLATE_COLOR`). Text sits
  over fills, outlines and bitmaps it has no say over, so solid text on a washed out plate is readable
  against all of them while still letting what it covers show through. Drawn last, after the outlines.
  That plate is a hit target as well as a background — see *Hit testing* below — so it is measured once,
  into `MeasuredLabel`, and the rectangle painted and the rectangle pointed at are the same value.
- **A child of the root is outlined heavily** (`ROOT_CHILD_BORDER_WIDTH`) and over every outline inside it,
  because the levels below cover their parent exactly: without it the boundary between two named blocks looks
  like every other edge on the map, and the map has no visible structure at the level it's named at.

Which also means a bitmap at that depth is named over its own picture, and one below it isn't named at all.

All of which is the treemap's problem and the radial view's, and **not the stack's**: a row is covered by
the row below it rather than by its own contents, so every row wide enough is named and given its size,
whatever depth it is at. See "The stack" above.

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

**`maxChildrenPerNode` doesn't apply to the node the viewport is rooted at**, which gets
`maxRootChildren` — half the cell budget — instead. A count that doesn't move when the room does makes
zooming pointless, and the pile is the one thing zooming exists for: the `LongSparseArray[]` of drawable
caches in `compose_leak.hprof` has 668 children, so it drew 200 and a pile of 468 whether it was a
sliver of the whole-heap-dump map or the whole viewport, and clicking that pile landed on the picture it
was clicked from. Rooted there it now draws 516 of them and a pile of 103, the ones still under the area
floor. The whole-heap-dump map is unchanged — 1,726 cells, 93 rectangles at the top level — because at
that root the area floor bites long before 200 does.

A radial layout has one more bound: **rings**. Eight around the centre disk, the width of each derived
from the viewport, so the picture always fills the circle and depth past that needs a zoom. Sectors
take their parent's whole sweep divided by weight, and a ring band is where a node's own name fits, so
the radial view has not needed the treemap's own-weight cell.

The stack's own bound is **rows**, `maxRows`, and its floors are lower than the treemap's — 6 dp to
subdivide, 2 dp to draw, against 12 dp and 3 dp. A level of a stack costs no width, so subdividing a
narrow block still buys a full row of detail, where in a picture that pays area for nesting it buys a
sliver of a sliver.

### A negative node id is not the tree's own

The tree's nodes are object ids, and the piles it invents — the uncollected garbage, and the class
groups — need ids of their own. **`nodeId < 0` is not the test for one**, and taking it for one is a bug that
looks like nothing: an object id is a heap address, a 32 bit dump records it in 4 bytes, and shark widens
those by sign, so **every object above the 2 GB mark of such a dump has a negative id**. `isPileId` is a range
check against `Int.MIN_VALUE` instead, and the pile ids start at `Long.MIN_VALUE`.

What the sign test cost, before it was a range check: on `large-dump.hprof`, **44 of the 4,616 rectangles of
the opening view** had `contains()` say the tree didn't hold them, so pointing at one selected nothing, the
chain pane never filled in, and clicking one went to the root instead of into it — with no error anywhere,
because every one of those answers is also a legitimate one. Their hex was wrong too (`0x-7deb3000`), which
is what `NodeIds.hexObjectId` is for and what makes such an id recognisable in the log.

The lesson for new code here: an id from a heap dump is an opaque 64 bit value, not a number to compare
against zero, and only a dump with objects past 2 GB will tell you otherwise. `highAddressHeapDump()` in
`HeapExplorerDumps` is built up there for exactly this — `dump { }` takes a `firstObjectId` so that a test
can ask for such a dump in one argument.

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

Clicking a node re-roots the treemap at it and re-runs the same layout against the full viewport, so
zooming is how deeper detail is reached, and the chain beside the map is how you get back out. See
"Hit testing" below for what a click resolves through, and `decisions.md` for why a click goes somewhere
rather than selecting in place.

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
  means `ROOT_CHILD_DEPTH`, the children of the node the view is rooted at, which is the same level the
  map names and marks off — so the blocks that are coloured apart are the blocks the reader is reading.
- **Reachability**: one hue per strength, shaded by depth. Says the most about the collector and the
  least about structure.
- **Slate**: blue greys only, for when the colours get in the way of the shapes.

Depth is unbounded, so shades cycle, which is fine as long as neighbours differ. A cell standing for many
objects rather than one — a class group, or the siblings that didn't fit — is a washed out version of its
strength, cool slate when that strength is `STRONG`, so it reads as "not an object" without needing a
colour of its own. All of it is in `CellColors.kt`, the one place the colours are named.

**The siblings that didn't fit are filled with dots on top of that**, `pileDots` in `CellView.kt`. That
rectangle is often the biggest thing on the map — a class group of 54,000 strings is one rectangle with
one of these filling almost all of it — and at that size a flat block reads as one enormous object, which
on a real dump means a bitmap. A texture says "many small things" before the label is read, and being an
even texture rather than a drawing of each of them keeps the pile looking like the one thing a click can
land on. It's a repeated `ImageShader` tile rather than a circle per dot, because the whole map is redrawn
every time the pointer moves onto another rectangle.

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

**A bitmap is labelled only if it's at the named depth**, like every other rectangle, and then over its own
picture on the translucent plate. Text straight over a picture read as neither, which is what the plate is
for; below that depth the chain beside the map is where a bitmap's class and size are read.

Two wiring details that are easy to get wrong: images are asked for per presentation, only for
rectangles at least `MIN_BITMAP_DRAW_SIZE` (8 dp) each way — below that an image is a smear of one
colour and it would still cost a heap dump read and a decode — and `bitmapImages` is a key of the
`remember` that measures the cells, since a cell arriving with pixels is a cell to draw differently.

## Hit testing

Cells are drawn into a single `Canvas`, so Compose has no per-cell node to hit test or to expose to
tests. Hit testing is therefore explicit: keep the laid out cells and resolve a click against their
geometry — deepest rectangle containing the point for a treemap, ring and angle for the radial view,
row and the block along it for the stack.

**Except that the deepest rectangle is not always the answer.** A subdivided rectangle is covered by its
own children, so `cellAt` takes an `edgeGrab`: within that distance of an edge, a container wins over
whatever shares it, which is the line the view draws there. A gap in a subdivision still belongs to the
node being subdivided. This is what a UI test clicks to reach a container — `clickContainerEdge` — since
the label bands it used to press are gone.

**And the name on a rectangle is a target of its own**, checked before the layout: `namedCellAt` in
`TreemapView`, against the plates measured for drawing. A name is written over everything nested inside
the rectangle it names, so the plate is the one piece of a subdivided rectangle still showing, and reading
the map is reading those names — pointing at one meaning the descendant under the lettering was the view
answering a question nobody asked. It lives in the app module rather than in `core` because only Compose
can measure text, and the plate is clamped to the rectangle it names, so a name on a cell barely a line
tall doesn't answer for the sibling below it. Everywhere else the innermost rectangle still wins.

**A single click goes to the rectangle it landed on**, and it's handled on press rather than on release:
with nothing here waiting for a second click, holding every click until the button came up would cost the
whole map a delay for nothing. So the whole map is one click deep, which is what replaced a double click
nothing announced. `detectOpenPresses` is what reads the press, since the views draw their cells rather
than composing them and so have no modifier to hang the gestures on — see `decisions.md`.

Where the map ends up isn't resolved here at all. A rectangle hands back a `Place` — `Place.of(cell)` — and
the map is laid out at `place.viewRootObjectId`, so a click on a rectangle is the same move as a click on a
line of the details panel or a row of a list, and the map is **rooted at the object clicked** rather than
zoomed along a chain to it. An object that dominates nothing is then one rectangle of its own bytes, which
is the honest answer to what it holds; how it is held is the chain pane's answer, not the map's. A group
isn't a node of the tree, so `Place.SmallerObjects` carries the parent it was left out of and roots the map
there, which is where those objects are and where the map has the room to draw the biggest of them one by
one.

Two things fall out of rooting rather than zooming. The tree is walked once, to draw the chain, instead of
once for the chain and once to find where to put the map — the path down a dominator tree is unique, so the
map root is a function of the object and there is nothing to keep in sync. And a view rooted at an object
the tree has no node for — a field can name one — falls back to the whole heap dump with a log line saying
so, rather than a walk that comes back empty.

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
