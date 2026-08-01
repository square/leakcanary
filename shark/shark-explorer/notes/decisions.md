# Decisions

Why the explorer is built the way it is. Append as decisions are made; correct entries that stop
being true rather than stacking contradictions.

## Compose Desktop, via Compose Multiplatform

Chosen over an HTML viewer or an Android-only UI so the treemap composables can eventually be shared
with `leakcanary-app`, and because the dominator computation is more comfortable on desktop than on
a phone (see `dominator-tree.md` for the memory numbers).

**Verified working:** Compose Multiplatform 1.11.1 with this repo's Kotlin 2.4.0, on Gradle 9.6.1 /
JDK 21. This was the main risk — CMP 1.11.1's artifacts are built against Kotlin 2.2.20 — and it
compiles and runs tests cleanly. The Compose compiler comes from Kotlin itself
(`org.jetbrains.kotlin.plugin.compose`), already on the root buildscript classpath for the Android
app.

Compose Multiplatform is not built for Java 8, which the rest of the repo targets, so
`shark-explorer-app` opts out of the root build script's Java 8 config and targets Java 17.
`shark-explorer-core` stays on Java 8 so Android can consume it.

## Two modules, not one

`shark-explorer-core` holds the whole pipeline — heap dump → dominator tree → treemap layout → hit
testing → navigation state — as plain JVM code with no Compose dependency. `shark-explorer-app` is
only composables and wiring.

Two reasons: the logic is then unit-testable without a UI harness, and it stays consumable from
Android later. Keeping Compose out of `core` is what makes both true, which is why `core` defines
its own rectangle type instead of using Compose's `Offset`/`Size`.

## Then a third, for the JDI client

`shark-explorer-jdwp` holds one class, `JdwpBitmaps`, and exists because of what it imports:
`com.sun.jdi` is a module of a desktop JDK and does not exist on Android. Putting it in `core` would be
the first thing in there that an Android consumer could not load, and the failure would be a
`NoClassDefFoundError` at the far end of a dependency rather than a build error here.

So `core` declares the `BitmapDebugger` interface and knows *when* a debugger is the way to get a
bitmap's pixels ([bitmaps.md](bitmaps.md)); this module knows *how*. `shark-explorer-app` is where the two
are put together, which is what a wiring layer is for. A module rather than dropping the class into
`shark-explorer-app` because "this needs a JDK" is a dependency boundary, and a boundary that only exists
in a comment is one nobody notices breaking.

## Depends on `shark-android`, not `shark`

Same as `shark-cli`, and for the details panel: `AndroidObjectInspectors` is what turns an object into
"Activity, destroyed" rather than just a class name, and it only exists in `shark-android`.

Note that `AndroidReferenceReaderFactory` and `AndroidObjectSizeCalculator`, which is what actually
makes the graph and the sizes right for an Android heap dump, are both in `shark` despite the names.
So the dependency is not what gets those.

## The heap dump gets a thread of its own

Not for safety: a `HeapGraph` is read only and safe to read from several threads at once. For latency.
Opening an 82 MB dump takes about six seconds — index, reachability, dominators — and afterwards
labelling a rectangle is still IO and walking up to the GC roots is still seconds of it.

`HeapDumpSession` owns a single thread executor and a `HeapExplorer`, and `read { }` is the only way
in. Confinement is then structural rather than a convention: the UI cannot touch the graph even by
accident, because it never has a reference to it. A composable holds `TreemapPresentation`s and
`HeapObjectSummary`s — values computed on that thread — and shows a spinner while the next one is on
its way.

There is a third place work goes, for the same reason: **decoding is neither the UI thread's nor the heap
dump thread's.** Reading a hundred bitmaps' pixels out of a dump has to be on the heap dump thread, but
turning them into images is PNG decoding and premultiplication arithmetic that would hold that thread up
behind every other read, so the read hands back bytes and `Dispatchers.Default` turns them into
`ImageBitmap`s. Fetching pixels off a device is a fourth: `Dispatchers.IO`, because it is minutes of
`adb` and not something the heap dump thread should be sitting in.

Cost of that choice: the layout runs on the heap dump's thread too, because labelling a rectangle
reads the object it stands for. Resizing the window therefore queues behind whatever else that thread
is doing. One thread is also a choice rather than a constraint — the graph would take a pool — so if
layout ever becomes the bottleneck, that's where to look.

## A read stops when nobody is waiting for it any more

One thread means a queue, and a UI asks questions it stops wanting: a window being dragged asks for a
layout per size it passes through, a pointer crossing a treemap asks about rectangles it has already left.
Every one of those that runs to its end is time the answer the user is waiting for spends queued.

Shark cancels its own work — `CancelSignal`, asked on every record read and at the long stretches of an
analysis — so the explorer doesn't have to find cancellation points of its own. `HeapDumpSession` opens
the heap dump with one signal for the life of it, and that signal reports the read currently in flight as
unwanted as soon as the coroutine that asked for it is no longer active. Which coroutine that is comes
from `withContext`, so cancelling a `LaunchedEffect` is all a caller does. A read given up on while it was
still queued never starts: the dispatcher drops a cancelled coroutine rather than running it.

Two consequences worth knowing:

- **Cancellation lands where the read reads.** A stretch that computes without reading finishes first. So
  the point of `HOVER_SETTLE_MILLIS` isn't gone — not starting a read still beats stopping one.
- **A read has to be safe to abandon half way.** Which today's are: the indexes built on first use are
  `by lazy`, whose initializers build a whole object before assigning it and don't cache a failure, so a
  cancelled build is retried rather than half kept. The walks reuse their arrays across calls by stamping
  entries with a generation per walk, so a walk that stopped mid-way leaves nothing to clear. Anything new
  that mutates state a later read depends on has to hold to that.

## One dominator tree, covering the whole heap dump

There is exactly one tree per open heap dump, built once, and it holds every object of the dump —
whatever a GC root reaches, however weakly, plus the garbage that hadn't been collected when it was
written. A `Map<Long, DominatorNode>` for a large dump is 100+ MB (see `dominator-tree.md`), so a tree
per subset of the reference strengths was never affordable; and a subset would answer a question nobody
asked, since the sizes say what's held how firmly without leaving anything out of the picture.

What the reachability checkboxes do instead is colour: unchecked greys everything held that firmly.
That's a repaint, not a rebuild, so it's instant and there is no strength it makes no sense to press.

## A log file per run, in `core` rather than in `shark-log`

Reports arrive as "it showed nothing" or "it hung", about a session that has ended. So every run writes
what it did to `~/.shark-explorer/logs`, and the reads are logged with their durations: without them a
report is a guess about which of half a dozen heap dump reads was slow, failed, or never came back.

`SessionLog` lives in `shark-explorer-core` and not in `shark-log`, next to `SharkLog` itself, because
`shark-log` is published with a tracked ABI and this would grow the public API of an artifact a great
many apps depend on, to serve a desktop tool none of them run. `core` is published nowhere, so it costs
nothing there. It takes a directory rather than choosing one, which is what keeps it Android-consumable
and unit testable; the app picks the directory.

A file per run rather than one file appended to: the question asked of a log here is always what one
session did. Every write is flushed, because the session worth reading is the one that ended by
crashing, and a buffered tail is the part that would have said why. The last line of a clean run is
`Shark Explorer closed`, which is how a session that ended is told from one that was killed.

## One heap dump per window

Opening a heap dump opens a window, so the windows on screen are the heap dumps open. A window never
swaps the dump it shows for another: it costs seconds and a 100+ MB tree to open one, and the trail
through it — where the map is zoomed, what's starred, what the panel is describing — is worth nothing
for a different dump. Replacing meant the second dump erased the first, which is exactly what
comparing two of them can't have.

The one window that takes a heap dump into itself is the one showing none, which is what the app
starts with when it was given no file: it's there to carry the button, and it has nothing to keep.

What follows: `heapDumpFile` is a window's, not `ExplorerApp`'s, because the window title is the file
name — several windows all called after the app say nothing about which one to switch to, and the OS
window list is the only way between them. `explorerWindows` and `openHeapDump` are plain state so the
rule is unit tested; the `application` block only draws a window per entry. Closing the last window
quits.

Where a window opens is ours to decide too, which `WindowPosition.PlatformDefault` was not doing:
measured on macOS, every window it places is centred, so two windows landed on exactly the same pixels
and the second looked like the first having been replaced — the very thing this is meant not to look
like. `cascadedPosition` centres and then steps down and right, as many steps as the screen has room
for before starting over.

The memory cost is per window and it isn't small — a tree, a graph and an index each — so N windows on
large dumps is N times the numbers in `dominator-tree.md`.

## Going back to the live device, through the `adb` command line

Reaching into the process that wrote the heap dump — which is the only place a native bitmap's pixels
still are, see `bitmaps.md` — goes through `adb` as a subprocess rather than through a library. The
alternative is `ddmlib` (or its successor, the `adblib` of Android Studio), which speaks the adb
protocol directly and would give typed devices and a real API. Not worth it here: what this needs is
five commands, `adb` is on every machine that has the SDK, and a dependency on Studio's internals is a
dependency on Studio's release cadence.

`Adb` is a `fun interface` over "run these arguments, get the output", which is what makes every step
above it testable without a device — `FakeAdb` in `shark-explorer-core`'s tests answers by command
prefix. **UI tests must pass a `DeviceHeapDumps` built on such an `Adb`**: the default one shells out to
the developer's `adb`, and a test that does that has whatever phone happens to be plugged in to answer
for.

**Nothing is picked automatically.** Both dialogs — `TakeHeapDumpDialog` and `BitmapsFromDeviceDialog` —
list the connected devices, then the processes of the one chosen, and wait. Each of those is a question
only the person at the window can answer: a fingerprint is one build of one model rather than one device,
and dumping the heap of the wrong process is seconds of someone's phone and tens of megabytes for the
wrong app.

**Taking a heap dump and fetching bitmaps are the same three `adb` commands**, which is why one class does
both. The difference is what happens to the file: a dump taken to be explored is kept, because the
explorer reads it lazily for as long as it's open and a dump that took a minute is worth reopening, while
one taken for its images alone is deleted as soon as they've been read. And because `dumpHeap` passes
`-b png` wherever the device supports it, a dump taken through the window needs no fetch afterwards —
the fetch is for dumps that came from somewhere else.

**A dump is how a device is asked for its bitmaps whenever it can answer that way**, even though attaching
a debugger would also work there. A dump costs the app the dump it was going to take anyway; a debugger
stops every thread of it and runs its code. So `fetchBitmaps` only reaches for `BitmapDebugger` below API
35, and the dialog says which of the two a device is in for before the button is pressed — an app freezing
mid-use is worth a warning.

**Below API 35, taking a dump offers the fetch in the same go**, as a checkbox next to the process in
`TakeHeapDumpDialog`. The pixels only exist in the live process, so the moment the dump is taken is the one
moment they are certainly still reachable — an hour later the app may be dead, and then the dump has
pictures nothing can ever fill in. Off by default, because it suspends the app for as long as compressing
every bitmap takes and there's no way to know from here how many that is; ticked when there's time.

**A failed fetch doesn't fail the dump.** The dump is tens of megabytes already pulled and the fetch is an
extra, so a debugger that can't attach leaves the dump opening normally, with the reason in the log and the
fetch still on offer from the window — which is where it would report the same failure again, in front of
someone who just asked for it. Losing the expensive thing over the cheap one would be the wrong way round.

## The pointer describes, the click goes there

Moving over a rectangle describes it beside the view; a single click **goes to** it, rooting the map there.
So reading a treemap is a sweep of the mouse rather than a click per rectangle, and the one thing a click
can mean is the one thing hovering can't do.

What the pointer describes is **a card beside the pointer itself, plus a few more steps on the end of the
chain pane**. The details panel stays on the object the map went to, however the pointer wanders — a
rectangle is pointed at to decide whether it's worth going to, and a window where every pane followed the
mouse was unreadable while the mouse was moving. So the pointer's answer is the card and the end of one
chain, and everything else holds still.

**Which object the pointer is on is said at the pointer**, in `PointerCard`: what a rectangle is, is the
question being asked by pointing at it, and an answer at the edge of the window is read by looking away
from the thing it is about. `placeCard` puts it after the pointer on both axes and flips to the other side
rather than sliding when that would leave the view, because the card is a Material `Surface` and a surface
the pointer ends up inside takes the hover off the map — which would close the card, and start over. Hence
also the gap, and hence nothing in the card being clickable.

**A rectangle that isn't one object gets the card too**, and needs it most. A pile is named on the map by a
count and a simple class name — `400 × Sibling`, `300 smaller objects` — which is all a rectangle has room
for, so the qualified class name, or which rectangle a leftover pile was left out of, fits nowhere but here.
The card used to be drawn for `Selection.Object` alone, which meant pointing at the one kind of cell whose
name is incomplete answered nothing at all.

Both are kept, as two sets of details from the same code: one for what the map is on, one for what the
pointer is on. Nothing is read when the pointer leaves, and nothing on the map screen is blanked as the
next cell is read, so a sweep across the map doesn't flicker.

Clicking selecting in place was the first version, with a double click to go anywhere. It went because the
double click was the only way through the map and nothing said so, while the single click's own answer — the
panels — is what a hover now gives for free.

What made this affordable, on the 38 MB dump in the repo, over all 4,572 rectangles of the opening view:

- `ReferrerIndex` — the pass that reads which object points at which, 399 ms — is **warmed up as soon as
  the first view is laid out**, rather than by the first question about a path. Without that the first
  hover pays for it, which is exactly the moment the app has to feel instant.
- Describing one object, which is a summary, its dominator and the chain from a GC root: **median 0 ms, p90
  10 ms, worst 105 ms.** The chain alone is at most 20 ms of that.
- A hover waits `HOVER_SETTLE_MILLIS` (100 ms) before reading anything, so a pointer crossing forty
  rectangles asks about the one it stops on rather than about all forty. Reads *can* be called off (see
  above), which caps what the thirty-nine cost, but not starting them is still cheaper than stopping them.
- `independentPathsBetween` and `independentPathsFromRoots` — every way an object is held, which is the
  expensive question — are **only asked for the object clicked**, once its chain has come back, and the
  answer is drawn into that chain. The pointer has nowhere to put a question that expensive.

## The chain from a GC root is a pane, not a popover

Hovering used to draw the tree's containers as a grey popover following the pointer. What it said was a
list of what the treemap already draws, in a shape that couldn't hold more, and it covered the picture.

Instead the chain is drawn the way a leak trace is (`PathDrawing.kt`): the shortest way a GC root reaches
the object, one row per object, with the steps that dominate it marked. Shortest in steps, so it's the
plainest way the object is held; the marked steps are the rectangles it sits inside, which is what ties the
chain to the picture. On the production dump the longest one is 34 steps, two of them views and the rest
RxJava plumbing — which is why it is cut at 20 and cut at the root end.

It sits **on the far side of the view from the details panel**, and only on the map screen: chain, view,
details, left to right — where the object came from, where it is, what it is keeping alive. A chain and the
details are both tall columns, so one pane holding both would always have one of them scrolled off, and
putting them either side of the map makes what was clicked and how it is held one answer around the thing
they are about rather than the window's two outer edges. Neither pane is drawn on the other screens: a list
of objects wants the width of the window more than it wants either of them.

**Every object of the chain is clickable, and that's how you get back out.** The ringed steps are the
rectangles the map is drawn inside, in the order it zoomed through them, so a click on one is a zoom back
out to it — `TreemapNavigation.zoomInto` of a node already on the path truncates rather than appends. A row
of breadcrumbs above the view used to be the way out; it went because it said a subset of what this pane
says, in a strip that couldn't hold a class name. **The whole heap dump is the top row of every chain**, for
the same reason: the way back to the screen the window opens on belongs where the chain says the whole heap
is, and a chain of the whole heap dump is that one row and nothing else.

**The screen bar has a button of the same name, which is not a duplicate of that row.** The chain is drawn
for the rectangle clicked, on the map screen only, so until something has been clicked, and from a list of
objects, that row isn't there — and the way back to the top of the tree is the one move that has to be
available whatever the window is showing. Hence a button beside the other screens rather than a second one
above the map: leaving the map and going back to the top of it are the same kind of move.

**The pointer's chain is drawn onto the end of the clicked one.** The rectangle under the pointer is inside
the one the window is describing, so the chain holding it *is* this chain plus a few steps — and drawing it
that way makes sweeping the pointer across the map read as the chain growing and shrinking, leaving the
reader the part they were already reading. `RootPath.stepsAfter` is the difference: the steps below the
object being described, or null when the pointer is on something that chain doesn't reach, which a click on
an object that dominates nothing leaves the map able to do. Then `RootPath.stepsBelow` cuts the pointer's
own chain at the rectangle the map is showing instead, with a dotted `PathCutRow` above it saying so — how
*that* rectangle is held is what going there would answer, and the pointer is not there yet.

It was a floating panel over the pane before this, lifted with a shadow and a border and labelled
`UNDER THE POINTER`, precisely so that it wouldn't read as one chain whose contents changed. Which is the
right worry for two chains that answer different questions and the wrong shape for two that answer the same
one: what holds this, from the whole heap dump down.

**What the pointer adds is condensed, because the whole of it has to fit beside the map.**
`PathDetail.BRIEF` drops the package, the address, the "instance"/"array"/"class" kind, the `Dominates ↓`
line and the field the object above holds each step in, keeping the retained size inline on the class name —
so it reads as a column of class names. `PathDetail.FULL`, the part of the chain that was clicked, keeps all
of them. Which field holds what is the question a reader has once they've stopped somewhere, and the
gutter's arrow already says that each step holds the next.

**The pane is scrolled to the bottom of the chain**, every time the chain grows, which is also every
rectangle the pointer moves onto. The end of it is the object the window is describing and the steps just
above it are how it is held; a pane scrolled to the top of a chain a dozen objects long is showing the least
interesting of it.

## A stretch of a chain that could have run some other way

Every path from a GC root to an object goes through every one of its dominators, so a run of steps *between*
two dominators is a run the chain didn't have to take: if it had, those steps would dominate the object too
and be marked. That is exactly where "held how else?" has an answer, and where the reader can be shown one
— `RootPathDetour`, and `RootPath.detours()` which cuts a chain into them.

So the answer is drawn **inline, under the step the stretch hangs below**: `N of M ways from here`, with an
arrow either side that switches which of them the chain runs through. `RootPath.drawnWith` does the
substitution in core rather than in the drawing, so that what is on screen is always one flat list of steps
— a chain drawn from its own steps plus a set of replacements is a chain whose rows and whose connecting
lines are worked out separately, and they have to agree.

This replaced a screen of its own, reached from a "N paths from the dominator" button in the details panel,
which drew every way an object is held side by side the full width of the window. Two problems with that:
it answered the question somewhere other than where the question is asked — the chain — and it made every
way its own chain from a GC root, most of which is the part they all share. A stretch is the part in doubt.

Each stretch is searched separately, `independentPathsBetween` for one below an object and
`independentPathsFromRoots` for one running off the top of a chain, where what holds it is a GC root rather
than an object. Both are asked once per click, after the chain arrives, because the chain is what says where
the stretches are.

## Which object it is, said the same way everywhere

Four surfaces name an object: a step of a chain, the card at the pointer, the bar above the map, a row of the
starred list. All four use `ObjectIdentity` — the class, then the class in full greyed under it, then its
address — because they are all answering the same question, and a reader who has learnt to skip the grey
lines on one should not have to learn where they are again on the next. The package on its own line is also
what keeps a row from wrapping in a pane 300 dp wide.

**Which object it is lives above the map, not in the details panel.** It is a different question from the
rest of that panel — which object, as against what that object holds — and it's the one line worth having on
the screens that have no panel. So the panel names no object of its own: it starts with the star and the
numbers.

**An address is printed as hex, everywhere.** A decimal object id matches nothing: `hexObjectId` is what
`shark-cli`, a leak trace and every other tool print, so it is what can be pasted between them.

**Nothing is coloured to say it leads somewhere.** Half the places the window names an object are nothing to
click, and colouring the other half meant the same line looking like two different things; `clickableRow`
says it with the hand cursor instead, which is where a reader looks for it.

## Only a move is a hover

A view reacts to `PointerEventType.Move` and ignores the `Enter` that comes with a pointer arriving. When a
view is composed under a pointer that hasn't moved — clicking a row of a list, which puts the map where the
row was — Compose sends it an enter carrying the pointer's position, and describing what that lands on
answers a rectangle nobody pointed at instead of the object just clicked.

Measured rather than assumed, and the measurement is worth keeping: in a Compose UI test, injecting one
`moveTo` produces an enter and nothing else, a second one produces a move, and `previousPosition` equals
`position` on every injected event — so `positionChangedIgnoreConsumed()` is false even for a real move and
cannot be what tells the two apart. Which is also why hovering in a test is two moves: see `hover()` in
`ExplorerUiTest.kt`.

Zooming, resizing and switching shape move the rectangles rather than the pointer, and no pointer event
follows at all, so each view remembers where the pointer is and works out what it is on again whenever it
is laid out anew.

## Testing split

Headless `runComposeUiTest` on the JVM covers the UI, so there's no emulator in the loop — a real
gain over the Android app's treemap, which can only be exercised on a device.

Because the treemap renders into one `Canvas`, UI tests cannot address individual rectangles. The
split that follows: layout, the adaptive-depth budget and hit testing are pure functions in `core`
with thorough unit tests; UI tests cover the wiring by clicking coordinates and asserting on the panes
either side of the view — the chain of objects holding what the map is on, and the details panel.

**A clickable identity block is one semantics node**, since `Modifier.clickable` merges its descendants, so
`onNodeWithText("com.example.Holder")` finds a whole step of a chain by any one of its three lines while the
same text in the bar above the map is a node of its own. Which is why several assertions here count nodes
rather than fetching one: the same object named in two places is two matches, and that is the window being
consistent rather than a test being loose.

**A cell's label is painted text, so it is nothing a test can see either**, which shapes even the waits:
`waitForTheTree` waits for the view with nothing left spinning, and a test that needs the map to have
*moved* waits on the log line a layout writes, since a map rooted somewhere else looks the same to
`onNode`.

A coordinate has to be worked out from what the window is actually showing, not written down as a
fraction of it. The view carries a `contentDescription` — `VIEW_DESCRIPTION`, which is also what a
screen reader gets for a canvas — and `ExplorerAppTest.viewBounds` reads its bounds, so every click is
a fraction of the view rather than of the window. Only the root keeps a label band, so `hoverRootBand`
is a fixed inset from the top of the view rather than a fraction of it, and `clickContainerEdge` clicks
the left edge, where a squarified layout puts the largest rectangle of every level. Window fractions
were what the first version used, and every one of them missed by a few pixels as soon as anything above
the view changed height — which the button row and the view controls both did.

Rendering rects as individual composables would give per-rect semantics and free hit testing, but at
a few thousand visible nodes the cost isn't worth it. Revisit if the node budget ends up much lower.
