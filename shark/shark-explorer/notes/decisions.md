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

## Testing split

Headless `runComposeUiTest` on the JVM covers the UI, so there's no emulator in the loop — a real
gain over the Android app's treemap, which can only be exercised on a device.

Because the treemap renders into one `Canvas`, UI tests cannot address individual rectangles. The
split that follows: layout, the adaptive-depth budget and hit testing are pure functions in `core`
with thorough unit tests; UI tests cover the wiring by clicking coordinates and asserting on the
details panel and breadcrumbs.

Rendering rects as individual composables would give per-rect semantics and free hit testing, but at
a few thousand visible nodes the cost isn't worth it. Revisit if the node budget ends up much lower.
