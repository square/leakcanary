# LeakCanary — agent guide

A memory leak detection library for Android, plus Shark, the heap analyzer underneath it. Published
to Maven Central and consumed by a very large number of apps, so **the public API and the bytecode
level are contracts**, not implementation details.

Not equally, though. The `leakcanary*` modules are what apps depend on directly, and their public API
is the contract that matters most: breaking backward compatibility there is a last resort. `shark*` is
used far less, so a breaking change is on the table there when it buys a meaningful improvement. In
both cases the ABI dump is what makes the break deliberate instead of a surprise, so propose it rather
than assuming it's fine.

This file records what an agent would get wrong from reading the source alone. Anything derivable by
reading the code belongs in the code, not here — please keep it that way when editing.

## Layout

| Directory | What's in it |
| --- | --- |
| `shark/` | Heap dump parsing and analysis. Plain JVM, no Android dependency, except `shark-android` which adds Android specific reference readers and matchers. |
| `object-watcher/` | Watches objects for retention. The lowest layer, usable on its own. |
| `leakcanary/` | The Android library, plus `leakcanary-app*`, a standalone UI app that is not part of the library. |
| `plumber/` | Fixes for known Android framework leaks, installed automatically. |
| `samples/` | Sample app. |
| `docs/` | The [documentation site](https://square.github.io/leakcanary/) content. |

The group directories (`shark/`, `leakcanary/`, …) hold no code of their own — only modules do.

## Build and test

`docs/dev-env.md` is the contributor setup guide — code style, local deployment, examples of the
synthetic heap dump DSL. Read it for anything this section doesn't cover.

Built with **Java 17**, targeting **Java 8 bytecode** repo wide. Both matter: consumers still on
Java 8 have to be able to use the artifacts.

```bash
./gradlew build                       # what CI runs
./gradlew :shark:shark:test           # one module's unit tests
./gradlew detekt                      # static analysis, also run by the pre-push hook
./gradlew updateKotlinAbi             # after any public API change, see below
./gradlew siteDokka                   # regenerate docs/api
```

Instrumentation tests need a device or emulator and only cover `leakcanary-android`,
`leakcanary-android-core` and `leakcanary-android-instrumentation`. CI runs them on one emulator per
major Android release, from the minSdk to the newest API level with a system image, so a change that
only works on some API levels will fail there rather than locally.

## Things that will bite you

**Public API changes fail the build until the ABI dump is updated.** `checkKotlinAbi` compares the
public ABI against the committed `api/*.api` files and runs as part of `check`, so `./gradlew build`
catches it. When it fails, run `./gradlew updateKotlinAbi` and commit the changed `api/*.api` files —
but read the diff first, because an unintended ABI change is exactly what this is meant to catch.
Modules with no public API are exempt; they're listed in `modulesWithoutPublicApi` in the root
`build.gradle.kts`.

**There are two ABI validation mechanisms, deliberately sharing task names** so that one command
covers the whole repo: the Kotlin Gradle plugin's `abiValidation()` for JVM modules, and an
equivalent pair of tasks hand-rolled in the root `build.gradle.kts` for Android library modules,
which KGP doesn't support yet ([KT-83410](https://youtrack.jetbrains.com/issue/KT-83410)).

**So use `checkKotlinAbi`/`updateKotlinAbi`, never `checkLegacyAbi`/`updateLegacyAbi`.** The `Legacy`
pair is what Kotlin's own ABI validation documentation calls these tasks, but here they come straight
from KGP and therefore exist *only on the JVM modules* — they silently skip every Android library
module, which is most of the published ones. A green `updateLegacyAbi` means less than half the repo
was covered.

**`docs/api/` is generated.** It's Dokka output committed to the repo, produced by
`./gradlew siteDokka`. Never hand-edit those files; fix the KDoc in the source instead.

**Some dependency versions are deliberately old.** The `compileOnly` AndroidX versions in
`gradle/libs.versions.toml` are pinned to the *lowest* version LeakCanary supports, so that apps
resolve to their own newer version without needing a resolution strategy. The inline comments say
which ones and why. Don't bump them to fix a warning.

**`HprofRetainedHeapPerfTest` and `HprofIOPerfTest` freeze exact numbers** — bytes read, and memory
retained at each analysis step, within a margin. A change to how the analysis allocates or reads will
fail them. That's the point: they exist to make memory and I/O regressions visible. Investigate
before adjusting the expected values, and say in the PR why the new number is correct.

**detekt runs on pre-push and in CI**, config at `config/detekt-config.yml`. The hook installs itself
via the `assemble` and `clean` tasks, so a fresh clone gets it after the first build. Run `detekt`
before pushing rather than discovering it at push time.

## Changelog

Entries go in `docs/changelog.md` under `## Unreleased`, each starting with one of the markers from
the legend at the top of that file. Pick the marker from what the change *is*, not from how big it
feels, and grep for a comparable existing entry rather than guessing.

**💥 means a crash fix here, not a breaking change** — the opposite of the
[gitmoji](https://gitmoji.dev/) convention, and the mistake that convention trips people into.
Reaching for 💥 because a change feels impactful tells readers a crash was fixed when nothing
crashed. Breaking changes are ⚠️; when one needs more than a bullet, write it as a
`### Breaking change: <summary>` heading with prose.

**The changelog is for changes that matter to the people consuming LeakCanary**, not a record of
every diff. Refactors, internal cleanups and test-only changes usually don't need an entry.

## Conventions

- When a function's parameters don't fit on one line, put **each on its own line** — the existing
  code is consistent about this and detekt won't tell you.
- Commit subjects are imperative and describe the change, e.g. "Keep modules without a public API off
  the documentation site". Explain *why* in the body when it isn't obvious.
- Don't leave test-only or unused code in the committed tree. If scaffolding was needed to get
  somewhere, remove it before the PR lands.
- Test heap dumps are built with the `dump { }` DSL from `shark-hprof-test` (see `docs/dev-env.md`)
  rather than committed as binary fixtures. Never hand-assemble hprof bytes. For a large realistic
  dump, drive a real JVM via `HotSpotDiagnosticMXBean.dumpHeap`.
- Tests use JUnit 4 and AssertJ. Instrumentation tests depend on `libs.assertjCore.android` rather
  than `libs.assertjCore`, because AssertJ 3.16 and up can't load on API 24 — the catalog comment
  says why. So an assertion that a unit test can use may not compile in an instrumentation test.

## Scoped guides

Subdirectories may carry their own `AGENTS.md`, and the closest one to the file being edited wins,
like `.gitignore`. Prefer putting guidance in the narrowest place it applies over growing this file —
a module's quirks belong next to the module.

Claude Code reads `CLAUDE.md` rather than `AGENTS.md`, so each `AGENTS.md` is paired with a
`CLAUDE.md` containing `@AGENTS.md`. Add both when adding a scoped guide.
