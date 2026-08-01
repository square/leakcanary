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

What the pointer describes is **the chain pane only**. The details panel keeps the window's edge and stays
on the object the map went to, however the pointer wanders — a rectangle is pointed at to decide whether
it's worth going to, and a window where every pane followed the mouse was unreadable while the mouse was
moving. So the pointer's answer is one floating chain over one pane, and everything else holds still.

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
- `independentPathsTo` — every way an object is held, which is the expensive question — is **only asked for
  the object clicked**, and it's the details panel that shows the answer. Which is a second reason that panel
  stays on the clicked object: the pointer has nowhere to put a question that expensive.

## The chain from a GC root is a pane, not a popover

Hovering used to draw the tree's containers as a grey popover following the pointer. What it said was a
list of what the treemap already draws, in a shape that couldn't hold more, and it covered the picture.

Instead the chain is drawn the way a leak trace is, by the same code the paths screen uses
(`PathDrawing.kt`): the shortest way a GC root reaches the object, one row per object, with the steps that
dominate it marked. Shortest in steps, so it's the plainest way the object is held; the marked steps are
the rectangles it sits inside, which is what ties the chain to the picture. On the production dump the
longest one is 34 steps, two of them views and the rest RxJava plumbing — which is why it is cut at 20 and
cut at the root end.

It sits **between the view and the details panel**, and only on the map screen. A chain and the details are
both tall columns, so one pane holding both would always have one of them scrolled off; the details panel
keeps the window edge it has always had, and the chain sits against the map it explains. The paths screen
draws chains of its own the full width of the window, and a list of objects wants that width more than it
wants a chain.

**Every object of the chain is clickable, and that's how you get back out.** The ringed steps are the
rectangles the map is drawn inside, in the order it zoomed through them, so a click on one is a zoom back
out to it — `TreemapNavigation.zoomInto` of a node already on the path truncates rather than appends. A row
of breadcrumbs above the view used to be the way out; it went because it said a subset of what this pane
says, in a strip that couldn't hold a class name.

**The pointer's chain floats over the clicked one**, inset a few dp with a shadow, rather than replacing it:
the two answer different questions — what is this thing I'm looking at, and what is that thing over there —
and a pointer wandering across the map shouldn't cost the reader the chain they were reading. Moving off the
map puts the pane back to the clicked chain with nothing read again.

**The floating one is condensed, because the whole chain has to fit next to the map.** `PathDetail.BRIEF`
drops the package, the "instance"/"array"/"class" kind, and the "Dominates this object" line, and keeps the
retained size inline on the class name — `PathDetail.FULL`, the pane under it, keeps all of them. It carries
a header of its own — simple name, full class name, id, reachability with its colour chip, retained, shallow
— because the details panel no longer follows the pointer, and reading what a rectangle is off the far edge
of the window while the chain explaining it sits next to the map is two places to look at once.

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
with thorough unit tests; UI tests cover the wiring by clicking coordinates and asserting on the panels
beside the view — the chain of objects holding what the map is on, and the details panel.

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
