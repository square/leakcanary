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

## One thread reads the heap dump

`HprofHeapGraph` has a single read cursor and a single LRU cache, so it is **not safe to use from two
threads**. Everything that touches a `HeapExplorer` — building a tree, laying it out, labelling a
rectangle, summarising a selection — therefore goes through `HeapDumpSession.read`, which confines it
to one thread of its own. Calling into `HeapExplorer` from a composable would work right up until it
corrupted a read.

What follows for the UI: a composable never holds a tree, only what was already computed from one. A
laid out, labelled view is a `TreemapPresentation` or a `RadialPresentation`, and a selection is a
`HeapObjectSummary`; both arrive a little after whatever asked for them changed.

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
- Build test heap dumps with the `hprofFile.dump { }` DSL from `shark-hprof-test` rather than
  checking in binary fixtures or hand-writing hprof bytes.

## Notes

Design decisions and findings, kept current as the work proceeds:

- `notes/decisions.md` — stack and structure decisions, with rationale
- `notes/dominator-tree.md` — dominator algorithm findings, memory/perf numbers
- `notes/treemap-rendering.md` — adaptive depth model, the two shapes, bugs in the existing Android
  treemap

Update these in the same change that makes them stale. They're for agents, so keep them short and
skip anything derivable from the code.
