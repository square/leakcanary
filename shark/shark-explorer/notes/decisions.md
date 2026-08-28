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

## An object is a place, and everything in the window is derived from it

Where a tab is, is **one value**: a `Place` in `shark-explorer-core` — an object, the pile of smaller
objects left out of one, the object list with its filter, the leaks, the starred. Everything drawn is a
function of it. The map is laid out at `place.viewRootObjectId`, the chain pane walks up to a GC root from
that same object, the details panel summarises it, the tab is named after it. Nothing else is stored.

Before this there were two coordinates — which screen the window was on, and a path the treemap had zoomed
along — and a dozen places that set one, the other, or both. What that cost was not the lines: it was that
a click on a rectangle, a row of a list, a field in the panel and a step of the chain each set them
slightly differently, so which panes agreed with each other depended on which of the four you had used.
Collapsing to one value is what makes **every way to an object the same move**, which is the whole point:
`Place.of(cell)` for a rectangle, `Place.Object(id)` everywhere else, and one `open` in
`HeapDumpExplorer` that the four of them go through.

**The second coordinate could go because it was never independent.** In a dominator tree the way down to an
object is unique, so the map root *is* the object — there is no path worth storing, and `TreemapNavigation`,
which stored one, is gone. What replaced going back out along it is the chain pane, which was already
drawing that path for a different reason.

**Which tab a click means is the click's own answer, not the target's** — `OpenIn`, decided in `OpenIn.kt`
and nowhere else. A plain click moves the tab being read, the way following a link moves a browser tab; a
middle click, a ⌘ or Ctrl click, and `Open in a new tab` from the right click menu open one **behind** what
is being read, because opening a tab that way is parking somewhere to come back to. The buttons on the bar
are the exception twice over: they always open a tab rather than moving one, and they open it in front,
since pressing one is asking to be somewhere else. Two lists of objects filtered differently are two useful
tabs, which is why the bar never reuses the tab of the same name.

The gestures are read twice because the views draw their cells rather than composing them: `Modifier.openable`
for anything composed, `detectOpenPresses` for the three views, both in `OpenIn.kt` so that a middle click on
a rectangle, on a ring, on a row of the stack and on a row of a list mean one thing. The right click menu is
the only part that is words, and it is what makes the other two findable.

**Every tab is closeable, the last one included.** A window with no tab still holds the heap dump it spent
seconds reading, and the bar above is one click from a tab again — closing tabs is never closing the dump,
which is the window's, see above. A tab's history is its own rather than the window's, or the back arrow
would walk out of the tab being read. And a tab id is counted up rather than reused, so a tab closed and
another opened are two tabs rather than one that changed its mind. **Right clicking an arrow lists that
history**, nearest first, because reading a heap dump is a dozen moves down into something and one move back
out: `NavigationHistory.backEntries` and `goBack(steps)` are that list and the click on the fourth entry of
it, and a click on an entry the history has since moved past is clamped rather than refused, the list and the
history being the same value one recomposition apart.

**A tab's name is a read of the heap dump**, class name plus address, because a strip of a dozen instances
of one class is only one you can pick out of if each tab says which instance it is. So the strip scrolls
rather than shrinking its tabs to nothing, and a tab shows a placeholder for the moment before the read
comes back.

**Three things in the window can say `Whole heap dump` at once** — the button on the bar, the tab, and the
top row of every chain — and they are three different moves: open a tab there, go to that tab, go there in
this tab. What tells them apart, to a screen reader and to a test, is the role: `Role.Button` on the bar,
`Role.Tab` on the strip, and no role at all on a row that navigates, which is a link rather than a button.
An assertion that means one of them says which.

**The three panes are resizable against the map and each foldable to nothing** (`Panes.kt`). Folding leaves
the button that unfolds it and nothing else, and folding the map is allowed too: a chain 30 steps long and
a details panel of 40 fields are each worth the whole window sometimes, and the map is the pane that can
always be got back to by unfolding. Widths are the window's rather than the tab's — a reader who has
widened the chain has widened it for the investigation, not for one object.

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

## Nothing collects the garbage unless `am dumpheap -g` asks for it

`am dumpheap` on its own writes whatever happens to be in the heap. There is no GC anywhere on that path:
`ActivityThread.handleDumpHeap` collects only when the `runGc` flag is set, which is what `-g` sets, and
ART's hprof dumper below it takes a GC critical section and suspends every thread rather than collecting.
So a dump taken without `-g` is the live heap *plus* everything that had become garbage since the last
collection, and the explorer draws all of it under `Unreachable`.

How much that is, measured on an API 29 `userdebug` emulator, dumping one real app's main process the
three ways one after another so that only the way of asking differs:

| | Dump on disk | Total | Strongly reachable | Unreachable |
| --- | --- | --- | --- | --- |
| `am dumpheap` | 127 MB | 95 MB, 1.56 M objects | 72 445 536 B | **18 047 114 B, 271 167 objects** |
| `am dumpheap -g` | 105 MB | 78 MB, 1.29 M objects | 72 462 345 B | **130 196 B, 4 240 objects** |
| `JdwpGc` | 106 MB | 78 MB, 1.31 M objects | 72 510 903 B | **798 329 B, 26 400 objects** |

The strongly reachable figure moving by 0.09% across the three is what says this is garbage rather than a
difference in what the explorer can *see*: the same live heap, with 18 MB of uncollected objects beside it
in one and not the others. Which also answers the other way it could have read — reference rules quietly
dropping paths and stranding live objects — with no: what survives a collection is `Cleaner`,
`NativeAllocationRegistry$CleanerThunk`, `FinalizerReference` and a TLS handshake's `DerInputBuffer`s, all
of it either genuinely pending finalization or allocated after the collection.

## Two ways of collecting, the same shape as the two ways of fetching bitmaps

**`am dumpheap -g` from API 27**, which is `System.gc()`, `System.runFinalization()`, `System.gc()` on the
dumped process's main thread — the same sequence as LeakCanary's `FinalizingInProcessGcTrigger`, minus the
100 ms that trigger sleeps in the middle to let the `ReferenceQueueDaemon` catch up. Nothing over `adb`
can add that sleep. This is the way whenever the device has it, because it costs the app the dump it was
going to take anyway.

**`JdwpGc` below API 27**, where `-g` doesn't exist — an older device answers `Error: Unknown option: -g`
and takes no dump at all, measured on an API 26 emulator, which is why
`AndroidDevice.canCollectGarbageBeforeDump` asks before passing it. It attaches the same way `JdwpBitmaps`
does (`JdwpSession` is what they share) and invokes `Runtime.getRuntime().gc()`, `Thread.sleep(100)`,
`System.runFinalization()`, `Runtime.getRuntime().gc()` in the process. So it *can* afford the sleep that
`-g` can't, and it uses `Runtime.gc()` rather than `System.gc()`, which on Android only collects every
other call: libcore's version sets a flag and leaves the collecting to the next `runFinalization()`. The
`-g` sequence works out because its three calls hand that flag between them; a caller picking its own
calls has no reason to depend on that.

**And it still collects less than `-g`**, by 0.67 MB in the table above, because the app runs between the
detach and the dump — dispose the connection, take the forward down, start `am dumpheap` — where `-g`
collects and dumps within the one call. Closing that would mean dumping from inside the session with
`Debug.dumpHprofData`, and an app can't write anywhere the shell can then read, which is the whole reason
`am dumpheap` hands it a file descriptor instead of a path. 95% of the way for one JDWP attach is the
trade taken.

**A collection that fails doesn't fail the dump.** Same rule as a failed bitmap fetch and for the same
reason: the dump is the expensive thing and the one that was asked for, and one with garbage in it is
still a heap dump — where a debugger that can't attach would otherwise mean no dump at all on the devices
that have nothing else. The reason goes in the log.

**`fetchBitmaps` deliberately collects neither way.** That dump is read for the pixels of bitmaps named in
a dump taken *earlier*, and collecting first is how a bitmap that is still in that one stops being in this
one.

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
pointer is on. Nothing is read when the pointer leaves, and nothing beside the map is blanked as the
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
chain to the picture.

**All of it, however long.** A chain cut short answers "what holds this?" with a count of the steps that
would have said, which is the one thing the pane exists not to do. Chains get long: 34 steps on the
production dump, and the dumps in the repo go further — 499 on `large-dump.hprof`, 1,518 on
`compose_leak.hprof`, both of them walks down a linked structure. What that costs, measured:

- **Reading one is a heap dump read per step**, 6 ms for the 499 and 12 ms for the 1,518, against a
  hover's budget of a hundred. Cheap because the walk that finds the chain is over `ReferrerIndex` and
  only the steps of the chain it found are read.
- **Drawing one is why the pane is a `LazyColumn`.** A `Column` with `verticalScroll` composes every row,
  and a chain of 1,500 rows at four lines each never finished drawing at all — 60 s and still nothing on
  screen. Lazy, the pane composes the seven rows it has the height for, so 20 steps and 500 steps are the
  same 200 ms from the click to the chain being there.
- **The stretches that could have run otherwise are still asked per chain**, and a chain that long has
  almost none: the deep ones are linked lists, where every step dominates the object and so is a step the
  chain had no choice about. One detour and 18 ms for the 499, none at all for the 1,518.

It sits **on the far side of the view from the details panel**: chain, view, details, left to right — where
the object came from, where it is, what it is keeping alive. A chain and the details are both tall columns,
so one pane holding both would always have one of them scrolled off, and putting them either side of the map
makes what was clicked and how it is held one answer around the thing they are about rather than the
window's two outer edges. Neither pane is drawn for a tab on a list: a list of objects wants the width of
the window more than it wants either of them, and there is no one object for them to be about.

**Every object of the chain is clickable, and that's how you get back out.** The ringed steps are the
objects the one being read sits inside, so a click on one is the way back out to it — the same move as
clicking its rectangle, since a chain step and a rectangle are both just an object to go to. A row of
breadcrumbs above the view used to be the way out; it went because it said a subset of what this pane says,
in a strip that couldn't hold a class name. **The whole heap dump is the top row of every chain**, for the
same reason: the way back to where the window opens belongs where the chain says the whole heap is, and a
chain of the whole heap dump is that one row and nothing else.

**The bar above has a button of the same name, and it is not a duplicate of that row.** The row goes to the
whole heap dump *in this tab*, keeping the trail that led here on the back arrow; the button opens a tab on
it, leaving this one where it is. A tab on a list has no chain pane at all, so the button is also the only
one of the two available whatever a tab is showing.

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

## The leaks are the explorer's own paths, not a leak trace

The `Leaks` screen finds the objects that shouldn't be in memory the two ways Shark finds them — the
`KeyedWeakReference`s LeakCanary left behind (`WatchedObjects`), and a pass over every instance with
`AndroidObjectInspectors.appLeakingObjectFilters` — and then answers everything else about them **with the
same code the rest of the window uses**: `HeapDominatorTreemap.rootPathTo`, the function that draws the
chain beside the map. No path here comes from `RealLeakTracerFactory` or the shortest path finder — though
what a leak is *called* does, since the chain is handed to a `LeakTrace` to be hashed, see below.

Two reasons, and the second is the one that decided it.

- A `LeakTraceObject` carries no object id, and a row you can't click is a dead end. Everything on this
  screen is an object to open.
- **A list that ranked leaks by one path and then showed another when a row is clicked is two answers to
  one question.** So the path is found once: which section a leak is in comes off that path — unreachable
  when `strengthOf` says so, a library leak when one of the path's own references carries a
  `LibraryLeakPattern`, the app's own otherwise — and clicking the row shows that path.

One walk up per object found, which on the 38 MB dump is 48 objects and **328 ms** with the index and the
candidates already paid for. That is what a walk *down* from the roots at open time would replace, and it
would replace it with work every dump does whether or not anyone opens this screen.

**The leaks are found as the heap dump opens**, not when something asks for them, and the map opens with
them shaded — a heap dump is opened to see what shouldn't be in memory, and a map that shows it only once a
box is ticked is a map that hides it. They ride along with the pass that builds `ReferrerIndex`, which
already ran on the read thread as soon as the map was up, so the map is still the first thing drawn and the
shading lands a moment later. Which is also why `CellColoring.DEFAULT` greys the strengths: that is what
`withLeaks` does either way, since a red shade over a map of pastels is one more pastel. On a dump whose
leaks retain almost nothing the map is then all grey — `unloaded_classes-stripped`, 4 objects out of
332,905 — and on one where they retain something it opens with the leak drawn as a red block.

**A leak is a reference, not a class**, and what groups the app's own leaks is the leak fingerprint
LeakCanary prints under one — `LeakTrace.leakFingerprint`, a SHA-1 of the suspect stretch of the chain,
from the last object known to still be needed down to the first one that shouldn't be there. Three things
it deliberately leaves out. **The class of what leaked**, because two objects reached through one bad
reference are one thing to fix whatever they are. **Everything below the first leaking object**, because
that is what the leak is *holding* rather than why — an activity and a bitmap eight references under it are
the same leak. **Which slot of an array an object landed in**, because that changes between two dumps of the
same app and a leak has to be the same leak in both.

It is computed by handing the chain to Shark rather than by applying that rule again here (`LeakFingerprint`
builds the `LeakTrace` the chain amounts to and reads its hash), because a leak fingerprint is only worth
printing if it is the same string as the one in a LeakCanary report of the same leak, and the rule for which
references count is subtle enough that writing it twice means finding out later that the two differ. That
was not a guess: this screen grouped by a rule of its own first, and it took four rounds of comparing
against LeakCanary to find every way the two differed. Library leaks are hashed the way `LibraryLeak` hashes
one, off the pattern they were recognized by. What the row is *named* after is still spelled here — the
first reference of the suspect stretch, `Holder.activity`, by the class declaring the field so that the name
is a string that is also on the chain drawn for it, where the leak fingerprint uses the class of the object.
Which is why the name is no substitute for the leak fingerprint and both are on the row.

**The row is named after both ends of that stretch**, in one line: `MortarScope.tearDowns → … →
QueueService.f$0`, and just `Holder.activity` when the two ends are the same reference, which is most
leaks. The first end is the reference that shouldn't be holding — **which is what LeakCanary calls a leak**,
`ApplicationLeak.shortDescription` being `suspectReferenceSubpath.first()` — and the last is the one that
points straight at what leaked, which is where to look on the chain to see it.

One line rather than two, because a second line that repeats the first whenever the stretch is one
reference reads as the row having two names. Both ends because either alone leaves rows that can't be told
apart: on `unloaded_classes-stripped` both app leaks start at `MediaStateMachine.observer` and end at
different references, and on a dump where two leaks are held by the same field it is the other way round.

**The row above them is the leak and the rows under it are the objects**, so the first object is always
shown and the rest are behind one row saying how many there are. Which is the shape of the thing: fifty
leaked instances of a screen are one reference to stop holding, and a list that scrolls for a minute to get
past them says the opposite — but a leak drawn with nothing under it says what shouldn't be holding without
ever saying what it is holding. The name ends on an arrow for the same reason: what the last reference
points at is the row underneath.

**Why an object is leaking is on the object, not on the leak.** An inspector reads it off the object —
`Activity#mDestroyed is true` — so two objects of one group can be leaking for reasons that don't read the
same, and a group has no business printing one of them as its own. What is left on the leak's row is what
is true of all of them: the references, the hash of them, how many objects and what they retain.

**A leak that can only be reached through another leak is dropped from the list**
(`foldedIntoWhatHoldsThem`). A leaked activity holds a leaked window which holds a leaked view tree, and
every one of those is an object an inspector recognizes, and there is one thing to fix. Nothing is lost by
it: they are all still on the map, shaded as leaking, and opening one draws a chain that runs through the
leak it went under.

**Only reached through, rather than "some other leak dominates it"**, which is what this used to ask. A
leak is a reference that shouldn't be there, not an object. An object held two ways, each of them through a
different leak, has no leaking dominator and survived that rule as a leak of its own — but both of the
references holding it are already on the list, fixing them takes it with them, and there is nothing about
it to add. `HeapLeaksTest` has that heap dump: a window two destroyed activities hold, which neither of
them dominates, since letting go of one leaves the other holding it.

It costs no second walk. `rootPathSearch` puts a leaking referrer in its last-resort queue (the third tier
below), so the chain it comes back with runs through another leak only when every chain does — the question
is answered by the chain the row already leads to. Which also makes a folded leak one whose own chain says,
on it, which leak holds it and why. Measured over the ten real dumps here, the two rules are
indistinguishable: same leaks, same leak fingerprints, same object counts on all ten. It is the tier that
made them agree — before it, the chain rule listed three objects on `compose_leak` where dominance listed
six.

**The leaks are checked against LeakCanary's own analysis of the same heap dump, by leak fingerprint.**
`HeapAnalyzer` with `FilteringLeakingObjectFinder(appLeakingObjectFilters)` and
`AndroidObjectInspectors.appDefaults` — the "every leak in the dump" analysis `shark-cli analyze` runs, not
the retained-since-the-last-dump one — against this screen. `LeakFingerprintTest` is that check on
synthetic dumps, one per difference that used to break it; the sweep over the ten real Android dumps in
this repo is worth re-running by hand after touching any of this, and it stands at **8 of the 10 dumps
agreeing exactly, 13 of the 15 leaks**. It is what found everything on this page: the counts alone matched
three rounds before the leak fingerprints did, because two chains can be different and the same length.

**Two dumps still differ, and neither is a tie-break nobody can explain.**

`compose_leak.hprof` — six objects hold `DefaultMainActivityScopeProvider`, two of them entries of the same
`MortarScope.tearDowns` `HashSet`, and the two tools take different ones. Both are deterministic and the
orders are unrelated. **LeakCanary walks down**, so when it dequeues the set it enqueues the entries in the
order Shark's `HashSet` reader reads the table, and the first of them claims the provider: that is
`ActivityDelegateNotifier`, third in the table. **The explorer walks up**, and `ReferrerIndex` yields the
referrers of an object highest object index first, so of the two it reaches the one further down the heap
dump: `DemoRootWithGatekeepersWorkflowProvider`, object index 31516 against 31263. Everything else about the
two ten-step chains is identical. Bucket order isn't stable across two dumps of one app either, so neither
answer is the right one — but a walk up cannot see a walk down's order, and the only way to break it the
same way is to walk down: one prioritized BFS from the GC roots at open time, filling a parent-per-object
array that every chain is then read out of. Every tie would then break the way a leak trace breaks it, since
it would be the same walk in the same direction, and a chain becomes a pointer chase up the array rather
than a search per hover. **It is no longer the cheaper of the two, though** — an int per object against the
three to six bytes an object `ReferrerIndex` now holds (`referrer-index.md`), where the linked list it used
to be was three times that. What it isn't is a tie-break: it is a second traversal at open time, reading
objects in BFS order where building the index is a sequential scan of the dump, and `ReferrerIndex` still
has to exist for "every way this is held".

`unloaded_classes-stripped.hprof` — two leaks, the same count as LeakCanary, and two leak fingerprints
that differ by their prefix. What is left is **an owner rule**:
`InternalLeakCanary.resumedActivity → HomeActivity` is weakened, because an `Activity` is owned by the
`ActivityThread$ActivityClientRecord` that holds it, so the explorer can't walk LeakCanary's ten-step
prefix and takes an eleven-step one from a static `MediaStateMachine` instead. That alone makes every leak
fingerprint on this dump differ, whatever else is fixed.

The fold was never the reason here — `BrowserToolbarView` is above both the leaking `CoordinatorLayout` and
the leaking `BrowserFragment`, and the layout is above nothing. This dump used to come out as **one** group,
from a tie broken by a rule rather than by an order. Below `BrowserToolbarView` there are two
four-step ways to the fragment: `container → CoordinatorLayout → SparseArray → Object[] →` it, and
`interactor → BrowserInteractor → DefaultBrowserToolbarController → lambda →` it. The explorer took the
first, so the fragment's chain ran through a leaking object, and a suspect stretch stops at the first
leaking object — so the fragment hashed to the layout's leak fingerprint and joined its group. LeakCanary
can't take that way at all, because **its phase 1 treats a leaking object as a leaf**.

**Where the two used to differ, and what closed it** — five rounds, in the order they were found:

- The suspect stretch ran past the first leaking object, and kept array indices, and named a reference by
  the class declaring the field rather than the class of the object. Three ways of grouping too finely.
- The fold looked at the steps a chain drew, then cut at twenty, rather than all of it, so a leak held by
  another one far above it stayed on the list.
- **Collections were read the way they are built.** `ArrayList.elementData → Object[] → [3]` where a leak
  trace says `ArrayList[x]`, and worse for a `HashMap`. `DataStructureReferenceReader` adds Shark's own
  readers for the dozen structures it knows, the way `ViewChildReferenceReader` adds a `ViewGroup`'s
  children: additively, so the table stays a node of the tree, and the dominator tree takes it back out of
  the middle. Costs about 11% of the time it takes to open a dump — 1.72 s against 1.55 s on the 38 MB one
  — for reading a collection's contents twice, which is what `ChainingInstanceReferenceReader` pays too.
- **The path search had no low priority queue.** It took `Thread.<local variable>` where LeakCanary took
  `AsyncTask.SERIAL_EXECUTOR`, which is a truthful answer to "what holds this" and a useless one — an object
  is on a stack because a method is running, and there is nothing to fix. `RootPathSearch` now puts off a
  stack frame, a known library leak and the arrays ART hangs off a class exactly as
  `PrioritizingShortestPathFinder` does, read in the other direction, and `ReferrerIndex` carries
  `Reference.isLowPriority` in a bit beside each referrer to answer it.
- **A reference into an object that shouldn't be in memory wasn't put off**, so a chain took it where there
  was a way round, and the object it led to hashed to the leak fingerprint of the leak on the way. That is
  a queue of its own in `RootPathSearch`, walked after the plain one for the same reason a stack frame is:
  a path through a dead object explains what holds an object about as well as a running method does. Where
  every way is a leak the chain still runs through one, which is what the fold above then drops. It reads as
  a different rule in LeakCanary — its phase 1 makes a leaking object a leaf, so the way round is the only
  path it can find at all — and breaks the same ties the same way.

  **It costs the pass that finds those objects, moved earlier**: they are needed before the first chain
  rather than when the Leaks screen is opened, which on the 38 MB dump is 296 ms once, paid by the first
  chain (963 ms against 627 ms) and by nothing after it. Two thousand chains take 1381 ms with the tier and
  1348 ms without, which is noise — the extra tier is one array read per referrer.

- **The two put-off kinds shared one queue, so between them the shorter way won**, and the shorter way to
  nearly anything is a stack frame. Measured on `leak_asynctask_o.hprof`, marking `SerialExecutor$1`
  `Expected` and `AsyncTask$3` `Stuck` — the two verdicts that make the chain name the faulty reference —
  turned `AsyncTask.SERIAL_EXECUTOR → SerialExecutor$1 → AsyncTask$3 → MainActivity$2 → MainActivity` into
  `Thread → <local variable> → MainActivity$2 → MainActivity`, the frame being two steps from the activity
  where the executor is six. That chain has no `Expected` step on it, so nothing crosses to `Stuck` and no
  reference is marked: the verdicts that identify the leak were what hid it. So the two kinds are now two
  queues, a leak above a low priority reference, because **a leak is an answer and a stack frame is not** —
  an object marked `Stuck` is a reader saying this is the thing to fix, and a frame answers "what holds
  this" with "a method is running".

  Costs one more int array the size of the dump, five in `RootPathSearch` now, and a `maxOf` per referrer.
  A/B on the 38 MB dump, same sample and same JVM settings minutes apart: the 2000 chains to its 2000
  largest objects, 1,713,405 steps and identical before and after, in 14.8–15.2 s ranked against 15.0–15.8 s
  unranked. Reading the steps out of the dump dominates that number — 857 steps a chain here — so read it as
  "no measurable cost" rather than as a measurement of the walk. The sweep below was re-run either side of
  it and gives the same leak fingerprints on all ten dumps.

  **What made it hard to see from the window is that nothing else offered the executor route.** The ways a
  detour could have run are node-disjoint paths (`independentPathsFromRoots`), and the executor route
  reaches `MainActivity$2` through `AsyncTask$3`, which the frame route already took — so it is not
  independent of one and never reported as an alternative. It was on screen only because the chain itself
  ran through it.

**Shark's library leak matchers are added to the reference reader the tree is built from**
(`ReferenceStrengthReader`), filtered to `LibraryLeakReferenceMatcher` — the ignored ones beside them would
drop references, and every object has to stay a node of the tree exactly once. A `LibraryLeakReferenceMatcher`
sets `Reference.isLowPriority` and `LazyDetails.matchedLibraryLeak` and nothing else, so the tree is the
same tree with the known leaks of it named, and the first of the two is what keeps a chain off a known
leaking reference while there is another way to the object.

**A leaking object's status is on every path, not only on the ones that turn out to be leaks.** Every step
of every chain carries a `LeakStatus`, worked out by `leakStatusesOf` from what the inspectors said about
the objects above and below it — Shark's own rule, minus the one that forces the last object of a leak
trace to be leaking, because a path here ends wherever the reader clicked. Green behind an object meant to
be alive, red behind one meant to be gone, and the reason in words underneath.

**The boxes above a view colour that view and nothing else.** A swatch beside an object — a step of a chain,
a row of a list, the details panel, the card at the pointer — is `objectStrengthColor`, off the strength
alone, while `legendColor` greys what the boxes have switched off. Greying a strength is a way of reading the
picture the view draws, and a line naming one object is not that picture: greyed there it would read as
saying something about the object. And `Leaking` unticks every strength, `withLeaks` / `withStrengths` being
the one place that is decided — a red shade over a map of pastels is one more pastel, and grey underneath is
what leaves the few objects that shouldn't be there as the only colour on screen.

**The treemap shades leaks without laying anything out again.** "Anything dominated by a dead node is dead"
is the whole rule, and cells arrive parent before child, so `CellColors.of` propagates it in one pass over
the cells it was already given. The one thing that pass can't know is whether the node the view is *rooted*
at is itself below a leak, which is one small read — `isBelowLeakingObject` — per view. There is no colour
for the objects that are meant to be alive: a treemap draws what retains what, and most of a heap dump is
objects nothing knows either way about. The chain says which is which, object by object.

Finding the leaks is a pass over every instance plus a walk up to the GC roots per object found, so it runs
once per heap dump, behind a screen someone asked for, and is capped at the largest
`MAX_LEAKING_OBJECTS` objects with a log line when it truncates.

## Which object it is, said the same way everywhere

Four surfaces name an object: a step of a chain, the card at the pointer, the bar above the map, a row of the
starred list. All four use `ObjectIdentity` — the class, then the class in full greyed under it, then its
address — because they are all answering the same question, and a reader who has learnt to skip the grey
lines on one should not have to learn where they are again on the next. The package on its own line is also
what keeps a row from wrapping in a pane 300 dp wide.

**Which object it is lives above the map, not in the details panel.** It is a different question from the
rest of that panel — which object, as against what that object holds — and it is what the tab strip and the
chain are both about, so it belongs between them. So the panel names no object of its own: it starts with
the star and the numbers.

**And it is set in a title there**, `nameStyle` being how `ObjectIdentity` takes a style it doesn't pick
itself: on that row the name is what the whole window is about, while everywhere else the same lines are a
mention of an object among others. At body size it read as one more label in a bar of them, and a window
whose subject is the smallest question on screen is one you have to hunt for the answer in.

**That row is two things with a rule between them**: how the tab got here — the arrows — and what it is on,
which is the title with what the notes offer under it. Without the rule it is one undifferentiated strip of
controls, and anything at the end of that reads as belonging to the window rather than to the object named
beside it; with it, the grouping is what says which, and nothing has to be labelled to say so.
`IntrinsicSize.Min` on the row is what makes the rule as tall as the row itself, the title being one line on
the whole heap dump and three on an object, with or without a button under it.

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

## A note belongs to the place, and the names in it are links

Every place takes a note, rather than the notes being a screen of their own. A note is written while reading an
object and it is about that object, so it belongs where the object is: a note you have to leave the object to
write is one written about however much of it you can still remember, and a note that isn't in front of you when
you come back to the object is one you forgot you had.

**The place's, not the tab's**, and the distinction is worth stating because every surface of this is drawn on a
tab: two tabs on one place are one note, and both show what the other is typing, since `ExplorerNotes` hands out
one `PlaceNotes` per place per run. A tab is a way of looking at a place and there can be several at once; what
a note is about is the place.

**And a place, not an object** — the word matters in the other direction too. Three of the keys are lists rather
than objects, one is a group of objects, and the tab a window opens with is the whole dump; "a note per object"
reads as if the object list and the leaks had none. The user facing word for it is *location*, since `Place` is
this codebase's rather than anybody else's.

**Between the row that says where the tab is and the panes that read it**, because a note is about the whole
of what the tab is showing: under the title it is about, above everything that describes it. At the foot of
the window it read as a note on whichever pane happened to be above it, which is a note about the details
panel or about the chain rather than about the object.

**Filed under what the tab is about, not under the tab.** `Place.noteKey()` is the whole of that distinction.
A place carries the state of its screen as well as its subject — what the object list is filtered to, which
leaks are unfolded, how many objects a pile of small ones stands for at this window width — and all of that
changes while reading. Keyed on the place itself, a note would follow the search box: typing a letter into it
would be moving to a different notepad, and the note just written would be filed under a half typed query
nobody will run again. So one note per object, one for the object list however it is filtered, one for the
leaks however they are unfolded. It also means a tab is not an identity a note can hang off: two tabs on one
object are one note, which is the same reason two windows on one dump are.

The note about the heap dump as a whole goes on the tab a window opens with, which is the whole dump as an
object like any other — so there is somewhere to write the story that isn't about one object without adding a
screen for it.

**Two states and no fold**: writing is a plain box with save and cancel, written is the note as it means, and
there is no third state, because a place nobody has written about has no section at all. What starts one is a
button under the title (`AddNoteButton`), which goes away as soon as there is a note — one way in on screen
at a time, and nothing spent on the places that will never have one, which is most of them. A note that exists
is simply on screen, so there is nothing to fold and nothing to remember folding: a note behind a button is a
note nobody remembers is there. The strip marks a tab that has one with a `✎` for the same reason — what makes
notes worth writing is the window saying where you have already worked something out. Which tabs
those are is one directory listing (`NoteDirectory.keysWithNotes`) rather than a file opened per tab, since it
is a question about the whole strip.

Which is also why **the window reads the file rather than the section**: whether there is a section at all is
the answer to that read, so a section that started it could only ever appear after itself.

**What a note can do is said in the box, not in the tooltip on the button.** A tooltip is read while deciding
whether to click, and a paragraph about markdown, class names, addresses and `shark://` links is in the way of
that decision; the same paragraph is exactly what is wanted once the box is open and empty, which is where the
placeholder is. So the button's hint says what clicking it does and stops.

**Why the button is under the title**, of the four places it could be: before the title puts the action ahead
of its subject, and makes the title's own position depend on whether a note exists. Hugging the end of the
title moves it on every click, the title being the object the tab is on. At the far end of the row it keeps one
position, but it is then as far from the title as the window is wide, and it read as the window's button rather
than as this object's. Under the title it is where its own result goes — the note opens exactly there — and it
reads in the order it happens: this object, then write about it.

What that costs is a line of every tab nobody has written about, which is why it is drawn small: `bodySmall` at
`ADD_NOTE_HEIGHT`, with no padding of its own, so that it starts where the title starts and adds a line rather
than a row. A `TextButton` still, rather than a clickable line of text, because it is an action and the role is
what a screen reader and a test tell it by.

**What makes a note worth keeping is that the names in it lead back into the window.** A note is mostly made
of things out of the heap dump — a class, an address, a link to the tab you were on — and typing those out
again as prose is what makes notes not worth writing. So `Note.of(text)` leaves every dotted name and every
`0x…` as a `NoteMention`, `HeapDominatorTreemap.referencesOf` asks the dump what they are, and
`Note.resolvedWith` turns the ones it recognises into links shortened to read as prose. What it doesn't
recognise stays exactly as typed: a class this dump has never heard of is a class somebody wrote about.

- **Parsed and resolved on save.** The box takes plain text while it is being typed — markdown is what gets
  typed there, and a box that reformats it as you go is a box arguing with you — so there is nothing to draw
  until there is something saved, and the resolve, which is a heap dump read, is one per save rather than one
  per pause in the typing. It only runs at all for a note that mentions something.
- **Resolving is idempotent**, because a note is resolved again every time the dump answers: what is drawn is
  built from the mention rather than from what the span is showing now.
- **An address is two `Long`s.** A 32 bit dump's ids are four bytes widened by sign, so `0x82182c00` is
  either that or the negative id `hexObjectId` prints as `0x82182c00`, and only the dump says which.
- **A `shark://` link keeps its link and gains a name.** Resolving a mention never replaces a link that is
  already there, so a link to an object reads as that object and still leads where it names — followed
  exactly the way one arriving from the OS is, which is what makes the same link work in a note, a chat
  message and an issue. A note outlives the run it was written in, which is why a link names the heap dump
  and not the window: see below.
- **Inline code is still read for mentions, a fenced block is not.** `` `com.example.Thing` `` is how anyone
  who writes markdown writes a class name; a fenced block is quoted rather than written.
- **One line is one block.** No two-space line endings, no blank line above a list. A note is written in
  lines, and this is how GitHub's comment box reads them.

**One notepad per place per run, not per window.** The same dump open in two windows is how two views of it
are compared, and two notepads over one file would have each window saving over the other's note with neither
ever showing the loss. `ExplorerNotes` is one per run, hands out one `HeapDumpNotes` per dump and one
`PlaceNotes` per place, so typing in one window shows up in the other.

**Saving is a button, and the draft is the run's.** Nothing is written until save, and cancel throws the draft
away: a box with those two buttons under it is a promise about which of the two happens, and an autosave with
a cancel button beside it is neither. What an autosave was covering is the typing somebody clicked away from,
which is why the draft lives in `PlaceNotes` — the run's notepad for that place — rather than in the section:
leaving the tab half way through a sentence and coming back finds the sentence. A draft is not filed when the
window closes either, which is the answer cancel gives and the same one. Nothing is saved until the file has
been read — an empty notepad because the disk was slow, written out, is a note deleted — and the button that
starts a note is disabled until then for the same reason. The save itself is `NonCancellable`, since the
section that asked for it is gone the moment the draft is.

**Markdown files in `~/.shark-explorer/notes`, a directory per dump.** Not beside the heap dump: dumps are
opened from device pulls, temporary files, read only mounts and checkouts of this repository, so writing there
means littering some and failing on the rest. Markdown, because a note about a leak ends up in an issue or
read by an agent more often than it is read here again. The directory is the dump's name plus a hash of the
directory it was in, since two runs of one app produce two `heap.hprof`s and they are two investigations;
inside it, a file per `noteKey` rather than one document with a section per place, so that a save touches only
the note that was typed into, nothing has to be parsed back out of a document that also holds somebody's own
headings, and the listing is the index.

## A link names the heap dump and nothing else

`shark://<file name>/<place>?<what the place needs>`. Two things were tried in front of that and both were
taken back out. The first version named the window — `shark://<window id>/<place>` — and it was wrong for the
reason a link exists: every place there is belongs to the heap dump, not to whatever is showing it, so a link
that named a window died with the window. Which is most links a day later, and most links in an agent's
session log, since a session outlives the run that wrote it. A link that mostly doesn't work is a link nobody
sends. The second carried the dump's path and then, briefly, the window as a refinement — `…/leaks?window=…`
— and both were paid for on every link that didn't need them, which is nearly all of them.

**Heap dump names are unique in practice**, which is what makes a name enough: a dump this app takes is
`<process>-<pid>-<random>.hprof` and LeakCanary's are `yyyy-MM-dd_HH-mm-ss_SSS.hprof`. So the cases a name
can't settle are rare enough to *ask* about, and asking is better than a link that carries an answer to a
question nobody had.

- **The authority is the file name**, because it is the part a person reads and types, it is what the window's
  title shows, and it is in every answer an agent has already been given.
- **Where the file is doesn't travel in the link.** `dump=%2FUsers%2F…` was four fifths of the characters of a
  link and the fifth nobody could read. So `HeapDumpPaths` writes down the path of every dump that opens, the
  newest 200 kept, and following a link is a lookup. What that costs is honest and small: a link works for as
  long as this machine remembers the file rather than for as long as the file exists, and a link about a dump
  that has been forgotten asks for the file — or can be given `&dump=<path>` by hand, which is also the answer
  for a dump this machine has never opened.
- **One record per heap dump**, named `heapDumpFileKey` — the `<name>-<hash of parent>` the notes and statuses
  are filed under — with the path inside it. The key is one-way, so the file name of the record can name a
  dump but never find one; the path it holds is what makes the lookup work. A file each rather than one file
  of all of them, because several runs open dumps at once and none of them coordinates: a whole-file write and
  a rename cannot be read as half of one.
- **Four outcomes, and the first is nearly always the one.** A window of this run has that dump: that window.
  None has, but the machine has had it open: the file opens. Two dumps of that name: ask which, by path. Name
  unknown here: ask for the file. `ExplorerWindows.open`.
- **The two questions are one dialog**, because both answers are a path — the places on record as rows, and
  the file picker under them. It is hosted in a window already showing one of the dumps in question when there
  is one, so asking which costs no window, and in an empty window otherwise, which is where the dump picked
  opens and which says why it is empty if the question is dismissed.
- **Window ids stay, and stay out of links.** They are what an agent calls a window, since one heap dump open
  in two of them is two places to be told about, and they stay random for that: a counted id repeats across
  runs and within one as windows close, which is an id that names the wrong window rather than none.
- **A run claims a link only for a window it already has**, never for a file it could open, or every run of
  the app would claim every link. The link is passed on exactly as it arrived, so what to do about a dump no
  window has — open it, ask which, ask where — belongs to whoever ends up holding it. `DeepLinkPeers`.
- **The agent surface converged on the same choice**: the tool argument is `heapDump`, taking a file name, and
  a window id only in the one case a name cannot answer, which is the same file open twice. `AgentTools`.
- **What it unlocked**, and the reason to reverse the first version rather than live with it: a `--no-ui` run
  answers `show` with a link now — it has no window and the file all the same — and every *Agent logs* row
  about another heap dump has a link to copy, where before there was nothing to send.

## A leaking status is the heap dump's answer until a hand overrules it

Every chain already carried a `LeakStatus` per object, worked out by Shark's inspectors and then propagated
along the chain — everything above an object still needed is still needed, everything a leaking object holds
is leaking. Two things were added to that: the status of the object a tab is *on*, said in the panel that
says what the object is, and the ability to overrule it.

**At the top of "What it is", under the object's name and above its size.** It went under the tab's title
first, beside the note button, and that was the wrong pane: the title row is about the tab, and this is a
conclusion about the object — the panel below it holds the evidence the conclusion was drawn from, so the
answer belongs at the head of that column rather than in a row of its own. Above the bitmap preview too, so
that a screenshot several hundred pixels tall can't push it out of the panel. In the colours the chain beside
it uses (`LeakStatus.background` and `textColor` are shared with `PathDrawing` rather than copied), because
it is the same answer read in one place instead of a dozen — a reader who has learnt the green and the red on
a chain reads them here for free.

**Under a header, because the panel labels every line**: `Verdict`, one word like the `Retained` and
`Shallow` beside it. It is the one line of the panel that is a judgement rather than a measurement, which is
also what says the pencil beside it is allowed to disagree with it.

**"Leaking" and "Not leaking" became `Stuck` and `Expected`**, `Unknown` staying `Unknown`, and **no word
built on "leak" is allowed on an object**. A leak is one faulty reference that should have been cleared, and
everything under it is retained by that one mistake — so `Leaking` or `Leaked` on twenty objects points a
reader at the twenty rather than at the one thing to fix. `Stuck` names the object's situation without
accusing it, and it is the only candidate that asks a question rather than closing one: something is holding
this, what? `Expected` says its presence in memory is legitimate at this point in the app's life. The pair is
deliberately not antonyms — an object in use can't be collected either, so the good side has to answer a
different question than "can it leave".

**No other analyser has a verdict like this**, so there were no words to borrow. Checked: JProfiler
classifies objects by reference type (strongly referenced, retained by soft references) and by age (`Mark
Heap`, then "new" and "old" objects); YourKit by reachability scope (strong, softly, weakly reachable,
unreachable, pending finalization); Eclipse MAT names places rather than objects (*leak suspect*,
*accumulation point*, *keep-alive path*); dotMemory has *key retention paths*. None labels an object leaking,
because none has watched objects or framework inspectors to do it with — they rank by size and leave the
judgement to the reader. What they do share is the frame: JProfiler asks whether objects "are still
legitimately on the heap or if a **faulty reference** keeps them alive", which is where the name for the
culprit edge comes from, and YourKit defines a leak as objects "not needed anymore according to the
application logic".

Two attempts came before this one. `Shouldn't be here` / `Meant to be here` was rejected on sight —
**a verdict is a label, not a sentence**, since it is read a dozen times down one chain. `Leaked` / `Needed`
was rejected for the misdirection above. One `LeakStatus.statusText` is where the words live, so the chain,
the panel, the dialog, the checkbox that shades them over the map and the reasons propagated along a chain
(`Activity↓ is expected`, `Activity↑ is stuck`) all say the same thing. The identifiers didn't move:
`LeakStatus`, `LEAKING`, `leakStatusesOf` stay Shark's names, because the code is where matching
`shark.LeakTraceObject.LeakingStatus` matters.

**The verdict means the same thing on an unreachable object.** A watched object nothing reaches any more is
`Stuck` like any other, even though what keeps it is the collector not having run rather than a faulty
reference: it was expected to be gone. Where it sits on that scale is what the leaks screen's `Unreachable`
section is for, and a fourth value would have made the verdict mean something different in one corner of the
window.

**"Faulty reference" is the name for the culprit**, the reference between the last `Expected` object and the
first `Stuck` one, which is what `LeakGroup.suspectPath` starts at and what the leaks screen names each row
after. **And the chain marks it**: `Holder.activity · faulty reference`, bold, in the red of the objects it
left behind, which is the change that actually puts a reader's eye on the reference rather than on the
objects. `PathReference.isFaulty`, worked out in `withLeakStatuses`, and `suspectSubpath` names the leaks
screen's rows off the same statuses — so where a leak is a single reference, a row there and the chain opened
from it name one thing.

**Only a single step between the two verdicts is marked.** `faultyReferenceIndexOrNull` asks for an `Expected`
object with a `Stuck` one directly under it, and marks nothing otherwise. The first attempt marked the top of
the suspect stretch instead, the way LeakCanary underlines all of it, and it was wrong in the case that
matters: a chain of `Cleaner`s with no verdict on any object of it had its top reference marked, which is a
reference named for being where the walk started rather than for anything read off the heap dump. Two shapes
make the stretch longer than a step and neither supports a mark — objects nothing knows either way about in
between, where the fault is at one of those steps and nothing says which; and nothing `Expected` above the
stuck object at all, where what holds it may be something that should have let go too, so the fault can be
further up than the path reaches. A guess drawn in the same bold red as an answer costs more than no mark,
because being the one line to act on is the whole of what the mark is for. Shortening the stretch is what
setting a verdict by hand does, and the mark appears when it becomes one step.

**Which makes the mark the exception on a real dump.** Measured over every `shark-android` test dump: 2 of
their 12 app leaks carry one — `MainActivity$Lol.foo` and `DvFragment.mRoot` — and the other ten have between
1 and 13 objects nothing knows either way about between the two verdicts, `AsyncTask.SERIAL_EXECUTOR` with its
four being the usual shape. That is the number to weigh if the rule is ever loosened: a rule that marked the
top of the stretch would put a bold red line on all twelve, and ten of them would be pointing at a reference
picked for being highest rather than for being wrong.

**Nothing is marked on a chain with nothing stuck on it**, which is most chains in a heap dump. A leak is a
reference the evidence points at, and there is no evidence until something below it is known not to belong.

**A pencil, left of the status, rather than a "Set by hand…" button.** It is what changes the answer, so it
belongs where the eye already is, and a text button pushed the reason onto a second line of a 320dp panel.
Disabled until the statuses have been read off disk, which is the same rule the button had.

**From the last step of the chain when there is one**, and from the object's own reading until the walk up to
the GC roots lands, since the chain's answer is the one with the objects above and below taken into account.
So the panel can say `Unknown` for a beat and then say `Stuck` — the panes filling in, not the window
changing its mind. Nothing at all for the tab a window opens with: the whole heap dump is no
object of it, and there is nothing to inspect or decide about.

**Loud for the two statuses that mean something, quiet for the third.** Most of a heap dump is objects
nothing knows either way about, so a shaded, bold `Unknown` on every object would be a line nobody reads by
the time it says something. `UNKNOWN` is small, muted and unshaded; the other two are shaded in
`TARGET_SHAPE`, the shape the chain marks its target with. A glyph as well as a colour (`✓ ? ✗`), so which
status it is doesn't rest on colour alone.

**Overriding always wins**, which is the one place this differs from how two inspectors disagreeing is
settled. There, the object still being needed wins, because two inspectors are two halves of the same
automated reading and the safer one is the one to believe. A hand is not that: someone who has read the code
knows what the inspectors can't, and weighing the two would mean a status that can't be set to the one an
inspector already picked. So `setByHandStatus` takes the reason someone typed and keeps the inspectors as the
record of what was overruled, exactly the way a conflict between two inspectors is recorded.

**A status without a reason is not a status.** `LeakStatusOverride` throws on a blank one and the dialog's
button is disabled until there is one. A status set by hand overrules the heap dump, so without the why it is
an assertion the next reader — a colleague, an agent, the same person in a month — has no way to check, and
one of those makes every other status in the dump worth less. `SET_BY_HAND` marks the reason wherever it is
read, so a green object somebody decided about is never mistaken for one an inspector recognized.

**A status set by hand is an argument to every read, not state of the tree.** The statuses of a chain are
worked out on every read of it, so `summarize`, `rootPathTo`, `independentPathsBetween`,
`independentPathsFromRoots`, `findLeaks` and `isBelowLeakingObject` all take a `LeakStatusOverrides`, and the
window's `LaunchedEffect`s are keyed on it — which is why that class has value equality. **A value rather
than state on the tree**, because the tree is read from one thread while the window is composed on another:
overrides living in the tree would mean a chain drawn from one set of them and the row above it from another,
with no way to tell. The cost of that choice is that a new question about a path has to take the parameter or
it silently answers with the dump's own reading, which looks right.

**The list of leaks is read through them too, which is the part that is easy to get wrong.** A chain is only
redrawn; the leaks are a *different list*. Mark an object leaking halfway up a chain and it becomes a leak,
and whatever it was holding drops off — that object is now only in memory because of this one, which is the
rule `foldedIntoWhatHoldsThem` already applied to what the inspectors found. Mark an object the inspectors
recognized as still needed and it leaves the list entirely, and what it was holding can become a leak of its
own. So the candidate set is the dump's own minus everything set to anything but `LEAKING` plus everything
set to it, `RootPathSearch` goes round what a hand marked exactly as it goes round what the inspectors did —
otherwise a leak would be grouped by a chain that disagrees with the statuses drawn on it — and the answer is
worked out per set of statuses and kept until the next one, since a status is set by hand and this is
seconds. The window asks again by keying that `LaunchedEffect` on the overrides like the rest.

**The price is the fingerprints.** A leak's name is `LeakTrace.leakFingerprint` of the suspect stretch — the
last object still needed down to the first one that shouldn't be there — so reading the list through
somebody's statuses moves both ends of that stretch and produces fingerprints that no longer match the ones
LeakCanary prints for the same leak. That is the deal: they match while nothing is set by hand, and moving
that stretch is the whole point of setting one. The alternative, a leaks screen that ignores what the reader
has established, is a screen that goes on listing an object they have already explained.

**Two statuses set by hand can contradict each other, and the contradiction is shown rather than settled.**
The propagation rules are what make it possible: a leaking object above forces everything it holds to be
leaking, and an object still needed below forces everything holding it to be needed. So two hand-set statuses
disagree when one of the objects is above the other, which is `HeapDominatorTreemap.reaches` asked **both ways
round** — one walk up `ReferrerIndex` per status already set, a question somebody asked rather than one the
pointer asks. `leakStatusConflictsWith` answers it before anything is written, and the dialog then lists every
one of them by name, with the reason it was given, because whoever is about to overrule it is the only person
who can weigh the two.

- **Reaching each other is not being above each other**, and it is ordinary rather than exotic: the sample
  app's `AsyncTask` leak is three objects on a loop — the task holds the thread running it through
  `FutureTask.runner`, that thread's frame holds the runnable the executor wrapped the task in, and that
  runnable holds the task. Asking `reaches` one way round there answers yes whichever pair and whichever
  direction, so the first version reported the canonical case as a conflict and named the two objects the
  wrong way round in it. Neither object on a loop is above the other — which one a chain shows first is
  decided by where the chain enters the loop — so `isAbove` asks both directions and a loop is no conflict.
  The chain still says so wherever it does put one above the other, which is a reason reading
  `Conflicts with`.
- **Flipping to the opposite status always resolves it**, which is why solving a conflict is one button.
  `EXPECTED` propagates upwards only and `STUCK` downwards only, so the pair that can disagree is
  always those two, and agreeing with the new status is the same as being flipped.
- **Flipped, not taken off**, so that what somebody typed is still in the file: the solved reason says which
  status it was, what it said, and that this is why it changed.
- **A status of `UNKNOWN` set by hand conflicts with nothing.** Nobody claiming to know overrules nobody, so
  it is never one of the statuses a new one has to be settled against — though it can still be overruled by
  the chain, and the reason then records what it was.
- **Nothing is written until the choice is made**, which is what makes "Undo" free, and the write is one
  `LeakStatusFile.write` of the lot rather than one per status: a save that stopped half way through would
  leave a heap dump whose statuses contradict each other, which is the one state this step exists to
  prevent. It runs `NonCancellable` because the dialog closes as soon as it has.

**One tab separated file per heap dump, in `~/.shark-explorer/leak-statuses`.** Named after the dump the way
its notes are, and beside them rather than next to the dump, for the same reason: dumps come from device
pulls, temporary files and read only mounts. A file rather than a directory of files, which is the opposite
of the notes — a note is a document somebody edits and a status is three fields the window writes, and every
question here is about all of them at once. Columns named in a comment at the top, the reason's newlines and
tabs escaped, the lines sorted by address, so that the file reads as evidence: two runs that set the same
statuses write the same file, and a line of it can be pasted into an issue. A line that can't be read is
skipped with a log line rather than thrown over — it is hand editable on purpose, and one typo must not be a
heap dump whose other statuses have gone. Addresses are written with `exactHexObjectId`, not `hexObjectId`,
since the latter gives up exactly what a file can't.

**Nothing is applied that wasn't written**, which is also the opposite of the notes beside it: a status only
this process knows about is a chain explained by a reason that will be gone next run. And nothing is saved
before the file has been read — an empty set of statuses, written out because the disk was slow, is every
status of that heap dump deleted — which is what the disabled button and the check in
`HeapDumpLeakStatuses.save` are both for.

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
