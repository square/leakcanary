# Shark Explorer — agent guide

A desktop app that renders a heap dump's dominator tree as a navigable treemap. The long term goal
is a YourKit-style heap explorer; the treemap is the first surface.

This file is scoped to `shark/shark-explorer/`. It only records things an agent would get wrong by
reading the source alone — everything else is in the code. Keep it that way.

## Modules

| Module | What it is | Constraints |
| --- | --- | --- |
| `shark-explorer-core` | Heap dump → dominator tree → treemap model. Layout, hit testing, navigation state. | **No Compose dependency, Java 8 target.** Must stay reusable from the Android `leakcanary-app`. |
| `shark-explorer-app` | Compose Desktop UI: window, treemap canvas, details panel. | **Java 17 target** — see below. |

`shark/shark-explorer/` itself holds no code, matching how `shark/` and `leakcanary/` are grouping
directories in this repo.

Put logic in `shark-explorer-core` by default. Anything in `shark-explorer-app` is hard to unit test
and can't be shared with Android, so it should be limited to composables and wiring.

## Use `LinkEvalDominatorTree`, not `DominatorTree`

`shark.DominatorTree` and `shark.ObjectDominators` on `main` compute dominators as a **BFS
approximation that is known to be wrong** — a cross edge can be processed while the parent's
dominator is still stale, so retained sizes get under-attributed. Do not build on them.

The correct implementation is `shark.LinkEvalDominatorTree` (exact Lengauer–Tarjan with link-eval).
See `notes/dominator-tree.md` for where it currently lives and its memory profile.

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
./gradlew :shark:shark-explorer:shark-explorer-app:run    # launch the app
./gradlew :shark:shark-explorer:shark-explorer-app:check   # test + detekt
```

`check` runs detekt (config at `config/detekt-config.yml`); CI and the pre-push hook both enforce
it, so run it before pushing.

## Testing conventions

- **UI tests are headless JVM tests**, not instrumentation tests. They live in `src/test/` and use
  `androidx.compose.ui.test.v2.runComposeUiTest`. Import from the **`.v2` package** — the non-v2
  `runComposeUiTest` is deprecated.
- **The treemap draws into a single `Canvas`, so there are no per-rectangle semantics nodes.** UI
  tests can't find rectangles by tag. Test layout and hit testing as pure functions in
  `shark-explorer-core`, and have UI tests drive coordinates with `performMouseInput` and assert on
  the details panel and breadcrumbs.
- Build test heap dumps with the `hprofFile.dump { }` DSL from `shark-hprof-test` rather than
  checking in binary fixtures or hand-writing hprof bytes.

## Notes

Design decisions and findings, kept current as the work proceeds:

- `notes/decisions.md` — stack and structure decisions, with rationale
- `notes/dominator-tree.md` — dominator algorithm findings, memory/perf numbers
- `notes/treemap-rendering.md` — adaptive depth model, bugs in the existing Android treemap

Update these in the same change that makes them stale. They're for agents, so keep them short and
skip anything derivable from the code.
