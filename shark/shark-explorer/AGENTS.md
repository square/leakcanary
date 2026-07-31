# Shark Explorer — agent guide

A desktop app that renders a heap dump's dominator tree as a navigable treemap, or as rings around a
centre. The long term goal is a YourKit-style heap explorer; these are the first surfaces.

This file is scoped to `shark/shark-explorer/`. It only records things an agent would get wrong by
reading the source alone — everything else is in the code. Keep it that way.

## Modules

| Module | What it is | Constraints |
| --- | --- | --- |
| `shark-explorer-core` | Heap dump → dominator tree → layout model. Layout, hit testing, navigation state. | **No Compose dependency, Java 8 target.** Must stay reusable from the Android `leakcanary-app`. |
| `shark-explorer-app` | Compose Desktop UI: window, the canvas each shape draws into, details panel. | **Java 17 target** — see below. |

`shark/shark-explorer/` itself holds no code, matching how `shark/` and `leakcanary/` are grouping
directories in this repo.

Put logic in `shark-explorer-core` by default. Anything in `shark-explorer-app` is hard to unit test
and can't be shared with Android, so it should be limited to composables and wiring.

## Use `HeapDominatorTree`, not `ApproximateDominatorTree`

`shark.ApproximateDominatorTree` is the on device BFS approximation, and it is **known to be wrong**
— a cross edge can be processed while the parent's dominator is still stale, so retained sizes get
under-attributed. Don't build on it.

`shark.HeapDominatorTree` is the exact one, which is what `HeapExplorer` uses. See
`notes/dominator-tree.md` for its memory profile and for the reference reader behaviour that makes a
treemap read strangely until you know about it.

## The heap dump is read off the UI thread

A `HeapGraph` is read only and safe to read from several threads at once, so the reason everything that
touches a `HeapExplorer` goes through `HeapDumpSession.read` is **latency, not safety**: labelling a
rectangle is IO, and summarising a selection or walking up to the GC roots is seconds of it on a large
dump. Doing any of that in a composable freezes the window.

What follows for the UI: a composable never holds a tree, only what was already computed from one. A
laid out, labelled view is a `TreemapPresentation` or a `RadialPresentation`, and a selection is a
`HeapObjectSummary`; both arrive a little after whatever asked for them changed.

The one thing that isn't thread safe is a `Sequence` a `HeapGraph` hands out — iterating one reads
through it — so a thread reading `graph.objects` needs its own rather than a shared one.

## Every run writes a log file

`installLogging()` in `shark-explorer-app` points `SharkLog` at stdout **and** at
`~/.shark-explorer/logs/shark-explorer-<when-it-started>.log`, one file per run, the newest
`SessionLog.KEEP_SESSION_COUNT` kept and the rest deleted as a run starts.

**So ask for that file when someone reports something odd**, and read it before guessing. It holds the
environment (JVM, OS, heap limit — a dump too large for the explorer runs out of exactly that), every
step of opening the dump with its duration, and every read of it through `HeapDumpSession.read` with
what was being read and how long it took. What that makes readable:

- A read logged as started and never as done is where the app was killed, hung, or ran out of memory.
- The last line being `Shark Explorer closed` is how a session that ended cleanly is told from one that
  didn't.
- Everything the window does silently — a path zoomed out because a node left the tree, a click landing
  on an object the tree has no node for, a list that came back empty — says so there rather than nowhere.

Which is also the rule for new code here: **anything the UI swallows or falls back from gets a
`SharkLog.d` line saying so.** The file is only worth reading if it's complete.

## Gradle facts that aren't visible from these build scripts

- **`shark-explorer-app` is excluded by name** from the repo-wide Java 8 target in the root
  `build.gradle.kts`, because Compose Multiplatform's artifacts aren't built for Java 8. If you
  rename or move the module, update that exclusion list or the build breaks confusingly.
- **Both modules are listed in `modulesWithoutPublicApi`** in the root `build.gradle.kts`. They are
  not published to Maven Central, their ABI isn't tracked, and they're left out of the docs site.
  So there is no `api/*.api` file to update and `updateKotlinAbi` doesn't apply.
- `compose` and `composeMultiplatform` in `gradle/libs.versions.toml` are **unrelated**: the first
  is the Jetpack Compose version the Android app builds against, the second is Compose
  Multiplatform for this desktop app.

## The app icon is generated, and its macOS shape is baked in

`shark-explorer-app/icons/shark-explorer-icon.svg` is the source, and
`shark-explorer-icon-small.svg` beside it is the **same shark with the gills, teeth and brow left
out**, for the sizes where those come out under a pixel. The `.icns`, the `.ico` and
`src/main/resources/shark-explorer-icon.png` are **rendered from the two of them** by
`icons/render-icons.sh`, which picks by size, so edit an SVG and re-run that script rather than
touching a binary. It needs `rsvg-convert` (`brew install librsvg`), and `iconutil`, which is macOS
only.

**The two drawings share one transform**, hard coded rather than fitted twice, so that the shark
doesn't shift when the dock crosses between them. Changing the shape in one means changing it in the
other and keeping that transform identical.

The SVG has the macOS app icon grid drawn into it — an 824x824 rounded body inside a 1024x1024
canvas, with its own shadow — because jpackage ships an `.icns` and **nothing masks or insets that for
us**, unlike an Android adaptive icon or a macOS 26 `.icon` bundle. So a redesign has to keep drawing
the body and the padding, and the corners are a superellipse rather than a circular arc.

**The macOS dock icon of a `./gradlew run` needs no runtime code.** The Compose plugin turns
`nativeDistributions.macOS.iconFile` into `-Xdock:icon` on the run task, verified by A/B: drop that
one line from the build script and the flag is gone from the run's JVM arguments and the default Java
icon is back. So `java.awt.Taskbar` has nothing to add here. `Window(icon = …)` does, but only for the
Windows and Linux title bar — macOS ignores it.

## Build and test

```bash
./gradlew :shark:shark-explorer:shark-explorer-core:test
./gradlew :shark:shark-explorer:shark-explorer-app:test   # UI tests, headless, no emulator
./gradlew :shark:shark-explorer:shark-explorer-app:check   # test + detekt

# Launch it. The path is optional; without one, use the "Open heap dump…" button.
./gradlew :shark:shark-explorer:shark-explorer-app:run \
  --args="shark/shark-android/src/test/resources/compose_leak.hprof"
```

The repo has real Android heap dumps to try it on: `shark/shark-android/src/test/resources/*.hprof`
and `leakcanary/leakcanary-android-instrumentation/src/androidTest/assets/large-dump.hprof` (39 MB,
the biggest one).

`check` runs detekt (config at `config/detekt-config.yml`); CI and the pre-push hook both enforce
it, so run it before pushing.

## Testing conventions

- **UI tests are headless JVM tests**, not instrumentation tests. They live in `src/test/` and use
  `androidx.compose.ui.test.v2.runComposeUiTest`. Import from the **`.v2` package** — the non-v2
  `runComposeUiTest` is deprecated.
- **Each shape draws into a single `Canvas`, so there are no per-cell semantics nodes.** UI
  tests can't find cells by tag. Test layout and hit testing as pure functions in
  `shark-explorer-core`, and have UI tests drive coordinates with `performMouseInput` and assert on
  the details panel and breadcrumbs.
- **`ExplorerAppTest` records `SharkLog` for every test**, not only for the two that assert on it. A log
  line is built from state — an index into a path, a node id — so a line built from the wrong state
  should fail the test that reaches it rather than wait for a session nobody can read. Which means a
  test that leaves `SharkLog.logger` set breaks the ones after it, hence the `@After`.
- **A click is a fraction of the view, never of the window.** `ExplorerAppTest.viewBounds` measures the
  view by its `contentDescription`, and every press helper is relative to that. Window fractions break
  the moment anything above the view changes height, which is a change to the top bar away.
- Build test heap dumps with the `hprofFile.dump { }` DSL from `shark-hprof-test` rather than
  checking in binary fixtures or hand-writing hprof bytes.
- **A synthetic Android class needs the fields the object inspectors read.** `HeapObjectSummary` runs
  `AndroidObjectInspectors`, and those read fields with `!!` — an `android.view.View` without `mParent`,
  `mWindowAttachCount`, `mAttachInfo` and `mContext` makes `summarize()` throw a bare
  `NullPointerException` from inside shark-android, which reads like a bug in the explorer.

## Notes

Design decisions and findings, kept current as the work proceeds:

- `notes/decisions.md` — stack and structure decisions, with rationale
- `notes/dominator-tree.md` — dominator algorithm findings, memory/perf numbers
- `notes/treemap-rendering.md` — adaptive depth model, the two shapes, bugs in the existing Android
  treemap

Update these in the same change that makes them stale. They're for agents, so keep them short and
skip anything derivable from the code.
