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
prefix. **UI tests must pass a `DeviceBitmaps` built on such an `Adb`**: the default one shells out to
the developer's `adb`, and a test that does that has whatever phone happens to be plugged in to answer
for.

**Nothing is picked automatically.** The dialog lists the connected devices ranked by how well each
matches the dump, and the processes of the one chosen, and waits. Each of those is a question only the
person at the window can answer — a fingerprint is one build of one model rather than one device, and
dumping the heap of the wrong process is seconds of someone's phone and tens of megabytes for pixels of
the wrong app.

## Testing split

Headless `runComposeUiTest` on the JVM covers the UI, so there's no emulator in the loop — a real
gain over the Android app's treemap, which can only be exercised on a device.

Because the treemap renders into one `Canvas`, UI tests cannot address individual rectangles. The
split that follows: layout, the adaptive-depth budget and hit testing are pure functions in `core`
with thorough unit tests; UI tests cover the wiring by clicking coordinates and asserting on the
details panel and breadcrumbs.

A coordinate has to be worked out from what the window is actually showing, not written down as a
fraction of it. The view carries a `contentDescription` — `VIEW_DESCRIPTION`, which is also what a
screen reader gets for a canvas — and `ExplorerAppTest.viewBounds` reads its bounds, so every click is
a fraction of the view rather than of the window. `pressBand` presses the label band of the nth
rectangle down the left edge, where a squarified layout puts the largest one of each level; a band is
`HEADER_HEIGHT` tall. Window fractions were what the first version used, and every one of them missed
by a few pixels as soon as anything above the view changed height — which the button row and the view
controls both did.

Rendering rects as individual composables would give per-rect semantics and free hit testing, but at
a few thousand visible nodes the cost isn't worth it. Revisit if the node budget ends up much lower.
