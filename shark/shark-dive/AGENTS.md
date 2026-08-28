# Shark Dive — agent guide

A desktop app that renders a heap dump's dominator tree as a navigable treemap, as rings around a
centre, or as a stack of rows the way a profiler draws a call tree. The long term goal is a YourKit-style
heap tool; these are the first surfaces.

This file is scoped to `shark/shark-dive/`. It only records things an agent would get wrong by
reading the source alone — everything else is in the code. Keep it that way.

## Modules

| Module | What it is | Constraints |
| --- | --- | --- |
| `shark-dive-core` | Heap dump → dominator tree → layout model. Layout, hit testing, navigation state. | **No Compose dependency, Java 8 target.** Must stay reusable from the Android `leakcanary-app`. |
| `shark-dive-jdwp` | Attaches to a live app as a debugger to read the pixels of its bitmaps. | **Imports `com.sun.jdi`, so it needs a JDK and can't be loaded on Android.** That's the whole reason it isn't in `core`. |
| `shark-dive-agent` | The MCP server a window answers agents through, the `--mcp-stdio` pipe and the `--agent` command line that reach it, and `--no-ui` for a run with no window at all. | **No Compose, Java 8 target, and desktop only** — it calls `ProcessHandle`. Has its own `AGENTS.md`. |
| `shark-dive-app` | Compose Desktop UI: window, the canvas each shape draws into, details panel. | **Java 17 target** — see below. |
| `shark-dive-eval` | The heap dumps an agent is measured on, and the scoring of what it did with them. Driven by `shark-dive-agent/harness/eval/run-eval.sh`. | **The only module with `shark-hprof-test` in its main source set**, which is why it is a module: writing the scenarios is what it does, and a dump-building DSL can be no dependency of anything the app ships. Runs no model. |

`shark/shark-dive/` itself holds no code, matching how `shark/` and `leakcanary/` are grouping
directories in this repo.

Put logic in `shark-dive-core` by default. Anything in `shark-dive-app` is hard to unit test
and can't be shared with Android, so it should be limited to composables and wiring.

## Use `HeapDominatorTree`, not `ApproximateDominatorTree`

`shark.ApproximateDominatorTree` is the on device BFS approximation, and it is **known to be wrong**
— a cross edge can be processed while the parent's dominator is still stale, so retained sizes get
under-attributed. Don't build on it.

`shark.HeapDominatorTree` is the exact one, which is what `HeapDive` uses. See
`notes/dominator-tree.md` for its memory profile and for the reference reader behaviour that makes a
treemap read strangely until you know about it.

## The heap dump is read off the UI thread

A `HeapGraph` is read only and safe to read from several threads at once, so the reason everything that
touches a `HeapDive` goes through `HeapDumpSession.read` is **latency, not safety**: labelling a
rectangle is IO, and summarising a selection or walking up to the GC roots is seconds of it on a large
dump. Doing any of that in a composable freezes the window.

What follows for the UI: a composable never holds a tree, only what was already computed from one. A
laid out, labelled view is a `TreemapPresentation` or a `RadialPresentation`, and a selection is a
`HeapObjectSummary`; both arrive a little after whatever asked for them changed.

The one thing that isn't thread safe is a `Sequence` a `HeapGraph` hands out — iterating one reads
through it — so a thread reading `graph.objects` needs its own rather than a shared one.

**Cancelling the coroutine that asked for a read stops the read**, which is what a `LaunchedEffect`
being relaunched does. Shark does the stopping, not us: the heap dump is opened with a `CancelSignal`
asking whether the read in flight is still wanted, and it's asked on every record read, so the work
gives up shortly after the question is withdrawn and comes back as a `CancellationException`. A read
given up on while it was still queued never starts at all. So dragging a window edge costs the size it
lands on and a little of each size it passed through, rather than all of them in full.

Two things follow. **A read is only cancellable at the granularity of what it reads** — a stretch that
computes without reading, like a layout over an already-labelled tree, stops when it next reads — so the
`HOVER_SETTLE_MILLIS` half of this, not starting work that isn't wanted yet, still earns its keep.
And **anything a read mutates has to survive being abandoned half way**: today's reads are safe because
the built-on-first-use indexes are `by lazy` initializers that build a whole object before assigning it
(a cancelled build is simply retried, since `lazy` doesn't cache a failure), and the walks reuse arrays
stamped with a generation per walk rather than cleared at the end.

**The pointer asks questions on that thread too**, because moving over a rectangle describes it. Which is
why nothing is read until the pointer has been still for `HOVER_SETTLE_MILLIS`, and why what a hover asks
for is index-backed: a chain from a GC root is one walk over `ReferrerIndex` and a read per step of what it
found, and the search for every way an object is held runs for the object clicked and no other. A new
question the panels ask has to be measured before it goes in the hover path — `notes/decisions.md` has the
numbers on the biggest dump in the repo, including how long a chain gets there.

## A verdict set by hand is an argument to every read, never state of the tree

**`STUCK`, `EXPECTED` and `UNKNOWN` are the only words for this, everywhere** — the enum, the window, the
`leak-statuses` files and the agent surface. Shark's `LEAKING`/`NOT_LEAKING` stops at the door: a person
watching an agent work has to be able to say the same thing about the same object as the agent, and a
vocabulary that changes at the edge of the process is one nobody can check across it. So don't reintroduce
either word, in an enum, a JSON value or a message — `LeakFingerprint` is the single place that maps to
`shark.LeakTraceObject.LeakingStatus`, and only because a fingerprint has to be the string LeakCanary
computes. `LeakStatus.statusText` is the case-only difference between the constant and a sentence.

None of the three is built on "leak" for a reason worth keeping: a leak is one faulty reference, and calling
everything under it leaking points readers at the wrong thing. The code still says `suspectPath` where Shark
does. `notes/decisions.md` has why each word won.

**An older `leak-statuses` file will have Shark's words in it** and its rows are skipped with a line in the
log saying which, since `LeakStatusFile` matches a status by name. That is the intended cost of having one
vocabulary; this app is an alpha and the files are three columns of text anybody can fix with `sed`.

**Which reference the leak is, is decided once, over the whole path** — `faultyReferenceIndexOrNull`, called
from `withLeakStatuses` — and carried on `PathReference.isFaulty` for the drawing to read. Working it out in
the window from the steps on screen looks equivalent and isn't: a pane draws stretches of a chain
(`stepsBelow`, `stepsAfter`, a swapped-in `RootPathWay`), so the object that ends the stretch can be above
what it shows.

**And it marks nothing unless one step crosses from `Expected` to `Stuck`.** `suspectReferenceIndexes`, the
whole stretch between the two verdicts, is what a leak is *named* after — `suspectSubpath`, and Shark's leak
fingerprint — so it is tempting to mark the top of it, which is what the first version did and what
`notes/decisions.md` records as wrong: a chain whose objects all have no verdict got its top reference marked
for being where the walk started. A longer stretch is a fault at one of several steps with nothing saying
which, and nothing drawn is the answer for that.

**And it is named in one place, `PathReference.leakLabel()`.** Four surfaces say which reference a leak is —
the row of the leaks screen, the `Leak solved` section above the chain, the `faultyReference` an agent is
answered with, and the note `conclude` writes — and a leak named `Holder.activity` by one of them and
`Holder#activity` by another is two leaks to whoever is reading, or grepping. `Owner.field` is not the whole
of it either: an array entry loses its index (`Object[][x]`, since which slot a leak was in is no part of
what it is) and a reference from a running method has no name of its own. `RootPath.faultyReference()` is the
matching single answer to "does this chain name one?", which is what both the window's section and the agent's
field are.

Someone reading a heap dump can overrule what the inspectors made of an object, and the statuses they set
are a `LeakStatusOverrides` **passed into every question whose answer they change** — `summarize`,
`rootPathTo`, `independentPathsBetween`, `independentPathsFromRoots`, `findLeaks`, `isBelowLeakingObject` —
rather than something the tree holds. It has to be that way round for the reason above: the tree is read from
one thread and the window composed on another, so overrides in the tree would draw a chain from one set of
them and the row above it from another.

**The parameter defaults to `LeakStatusOverrides.NONE`**, so a new read that forgets it compiles and answers
with the dump's own reading — which looks right, and is wrong the moment anybody has set a status. Thread it
through, and key the `LaunchedEffect` that asks on the overrides, which is what makes setting one redraw.

**`findLeaks` is one of them, and the least obvious**: setting a status changes *which objects are leaks*,
not only how one of them reads. Mark something leaking halfway up a chain and it becomes a leak, while
whatever it holds drops off the list — that object is now only in memory because of this one, which is what
`foldedIntoWhatHoldsThem` folds. The list is worked out again per set of statuses and kept until the next,
and `RootPathSearch` goes round what a hand marked leaking exactly as it goes round what the inspectors did,
so the chain a leak is grouped by and the statuses drawn on that chain are one answer. The price is that a
`LeakGroup.leakFingerprint` only matches LeakCanary's for the same objects while nothing is set by hand;
`notes/decisions.md` has the rest.

## A heap dump can have `android.os.Build` and not the fields Shark reads off it

`AndroidBuildMirror.fromHeapGraph` reads `MANUFACTURER`, `ID` and `VERSION.SDK_INT` with `!!`, and nearly
every one of Shark's library leak patterns decides whether it applies by asking it. Those patterns are
filtered against the graph when a `ReferenceReader` is *created*, which in Shark Dive is while the
dominator tree is being built — so a dump that has the class and not the fields throws a bare NPE from
under everything, and what you see is a window that spins for ever and never draws a tree.

Real dumps have all three. Synthetic ones written with the `dump { }` DSL have whatever the fixture wrote,
which is why `ReferenceStrengthReader.recordsAndroidBuild` checks the fields rather than the class, and
why the test fixtures that write an `android.os.Build` write `ID` too even though nothing in the window
shows it.

## Every run writes a log file

`installLogging()` in `shark-dive-app` points `SharkLog` at stdout **and** at
`~/.shark-dive/logs/shark-dive-<when-it-started>.log`, one file per run, the newest
`SessionLog.KEEP_SESSION_COUNT` kept and the rest deleted as a run starts.

**So ask for that file when someone reports something odd**, and read it before guessing. It holds the
environment (JVM, OS, heap limit — a dump too large for Shark Dive runs out of exactly that), every
step of opening the dump with its duration, and every read of it through `HeapDumpSession.read` with
what was being read and how long it took. What that makes readable:

- A read logged as started and never as done is where the app was killed, hung, or ran out of memory.
- The last line being `Shark Dive closed` is how a session that ended cleanly is told from one that
  didn't.
- Everything the window does silently — a path zoomed out because a node left the tree, a click landing
  on an object the tree has no node for, a list that came back empty — says so there rather than nowhere.
- A run is every window of it, and a window is a heap dump, so the reads of several dumps interleave.
  The `[heap-dump-<file name>]` a line was written from is which dump it is about; lines from the
  window's own thread name the file instead.

Which is also the rule for new code here: **anything the UI swallows or falls back from gets a
`SharkLog.d` line saying so.** The file is only worth reading if it's complete.

## This app has its own version line, and no number of it can mean "alpha"

`SHARK_DIVE_VERSION` in `gradle.properties`, not the repo wide `VERSION_NAME`, and the two are released
independently — `shark-dive-*` tags against `v*` tags. See `docs/releasing-shark-dive.md`.

It isn't only a preference. Every installer format validates the version, and what they leave between them,
each measured by building it, is **`MAJOR.MINOR.PATCH` with MAJOR from 1 to 255**, MINOR up to 255, PATCH up
to 65535:

- `3.0-alpha-10` — `Illegal version for 'Dmg'`, and for `Msi`. No qualifiers, in any format.
- `0.1.0` — fails `createDistributable`, and **not through the version validation the other two trip**:
  the Compose plugin accepts it and jpackage then reports `Bundler Mac Application Image skipped because
  of a configuration problem: The first number in an app-version cannot be zero or negative`. So the
  message says "skipped", is only in `build/compose/logs/createDistributable/jpackage-*-err.txt`, and the
  Gradle failure above it names no version at all.
- `2026.8.0` and `256.0.0` — `Illegal version for 'Msi'`, whose fields cap at 255.255.65535.

So `0.x` is not available and neither is a calendar version, and "this is an alpha" is said by the release
being a prerelease titled that way. Don't try to encode it in the number, and don't split it across
`macOS.packageVersion` and `macOS.packageBuildVersion` either: the user visible one is
`CFBundleShortVersionString`, which is also the field Munki compares for Managed Software Center updates,
so pinning it would freeze updates for everyone who installed from there.

**The version reaches the app as a generated resource**, `shark-dive-version.properties`, written by
`writeVersionResource` and read by `SharkDiveVersion`. Not the jar manifest: `run` and the tests put
class directories on the classpath rather than the jar, so `Package.getImplementationVersion()` is null for
every way this app is launched while being worked on, and the update check would only be exercisable from a
packaged build. `SharkDiveVersionTest` exists because a broken wiring here fails silently — the version
becomes `unknown`, the check declines to run, and no window ever mentions an update.

## The update check reports and nothing else

`UpdateCheck` fetches one file and, if it names a later version, `UpdateNotice` puts a bar in every window
of the run. Nothing downloads or installs. Three things about it that reading the code won't tell you:

- **`releases/latest` is the wrong release.** This repository publishes LeakCanary libraries on `v*` tags
  and Shark Dive on `shark-dive-*` tags, and GitHub has one "latest" pointer per repository, so that
  endpoint answers with whichever line released last. Verified: it returns `v3.0-alpha-9`.
- **The unauthenticated GitHub API allows 60 requests an hour per IP**, and Block's shared egress can
  exhaust that on other people's runs. A release asset download is unmetered, which is why the manifest is
  one.
- **The manifest is written by `promote-shark-dive.yml` and nothing else**, so publishing a release and
  offering it to everyone are two separate acts. `UpdateCheckTest` pins the manifest format from this side,
  because the workflow writes it in bash and nothing else keeps the two agreeing.

`UpdateNotice` is one per run rather than per window, so dismissing the bar in one window clears it in all
of them.

## A `shark://` link never reaches a run from Gradle, and that is not a bug in the code

`DeepLink` is the URL, `DeepLinkScheme` is the OS end of it and `DeepLinkPeers` is how a link crosses
from one run of this app to another. What reading them won't tell you is that **none of it can be tried
from `run` or `runNamed`**, on any platform, and the reason is different on each:

- **macOS delivers to a bundle identity.** A JVM launched from a shell script — which is what `runNamed`'s
  generated bundle is — registers with LaunchServices as `net.java.openjdk.java` whatever the wrapper's
  `Info.plist` says. Measured with `lsappinfo find pid=<pid>`. Only a launcher `jpackage` built gets its
  own `CFBundleIdentifier`, and the Apple Event carrying the URL goes to the identity, so a `runNamed`
  process is handed nothing however well its plist declares the scheme.
- **Windows and Linux are registered at runtime**, by `DeepLinkScheme.registerWithTheOs`, and it declines
  for a run whose executable is `java` — registering that would tell the OS to open links with a JVM and
  no classpath.

So the loop for anything about links is a package, not a compile:

```bash
./gradlew :shark:shark-dive:shark-dive-app:createDistributable
cp -R "shark/shark-dive/shark-dive-app/build/compose/binaries/main/app/Shark Dive.app" \
  ~/Applications/
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister \
  -f ~/Applications/"Shark Dive.app"
open -a ~/Applications/"Shark Dive.app" --args --title="Links" path/to/dump.hprof
open "shark://dump.hprof/leaks"
```

A link names the heap dump and nothing else, so that is the whole recipe — no id to read out of the log
first, and the same line works after the run that opened the dump has ended, which is the case worth trying.

**A link says no path, so a dump this machine has never opened is a question rather than a jump.** Where each
dump was is written down under `~/.shark-dive/heap-dump-paths`, one file per heap dump opened, and the
`HeapDumpPaths` lookup is what turns a file name back into a file. A link about a name nothing there has, or
one whose record has been evicted, opens a window asking for the file — and a name two records share asks
which of them. Both are dialogs waiting on somebody, so a link that "did nothing" is worth a look at the
screen: `ls ~/.shark-dive/heap-dump-paths` says which of the two it will be, and `&dump=<path>` skips the
question for a dump nothing here has opened.

**Read the result in the log rather than off the screen.** Following a link raises the app over whatever
the person at the machine was doing, so a screenshot to check it worked costs them their window and shows
you theirs. `The OS handed this run`, `A link asked window <id> for <place> of <file>`, `A link asked for
<place> of <file>, which is not open yet`, `A link to <place> of <file> is asking: <question>` and `A link
asked this window for <place>` are the lines that say a link was delivered, routed, asked about and opened
as a tab.

A run from source is still *reachable*: every run publishes a loopback port under `~/.shark-dive/runs`,
and the installed app hands on any link it has no window for. That is what makes a link to a
`./gradlew run` window work — the installed app is the courier, so there has to be one. It claims a link
only for a heap dump it *already has open*, never for a file it could open, or every run would claim every
link.

**Deliberately not single instance.** Several Shark Dive windows open at once is how this app is used, so a run
holding a link asks each of the others in turn rather than the second run handing its command line to the
first and exiting.

## Gradle facts that aren't visible from these build scripts

- **`shark-dive-app` is excluded by name** from the repo-wide Java 8 target in the root
  `build.gradle.kts`, because Compose Multiplatform's artifacts aren't built for Java 8. If you
  rename or move the module, update that exclusion list or the build breaks confusingly.
- **All five modules are listed in `modulesWithoutPublicApi`** in the root `build.gradle.kts`. They
  are not published to Maven Central, their ABI isn't tracked, and they're left out of the docs site.
  So there is no `api/*.api` file to update and `updateKotlinAbi` doesn't apply.
- **`jdk.jdi` is listed in the app's `nativeDistributions.modules`.** jlink includes only the JDK
  modules it detects a use of, and it detects none through `Bootstrap.virtualMachineManager()`, so a
  packaged build without that line attaches to nothing.
- `compose` and `composeMultiplatform` in `gradle/libs.versions.toml` are **unrelated**: the first
  is the Jetpack Compose version the Android app builds against, the second is Compose
  Multiplatform for this desktop app.

## The app icon is generated, and its macOS shape is baked in

`shark-dive-app/icons/shark-dive-icon.svg` is the source, and
`shark-dive-icon-small.svg` beside it is the **same shark with the gills, teeth and brow left
out**, for the sizes where those come out under a pixel. The `.icns`, the `.ico` and
`src/main/resources/shark-dive-icon.png` are **rendered from the two of them** by
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

**`-Xdock:icon` is what puts the icon on the tile, and a bundle around the JVM is not a substitute.**
AWT sets the dock tile from that flag as it starts, and when the flag is absent it sets the tile to
the Java icon — over whatever the bundle asked for. So `runNamed` passes the flag too, even though its
generated bundle declares `CFBundleIconFile`. `CFBundleIconFile` is not ignored, it is just overwritten:
`NSRunningApplication.icon` for a `runNamed` process without the flag hands back the shark, because
that is the LaunchServices record, while the tile on screen is Duke. **Which is the trap** — every API
an agent can read says the icon is right, and only a picture of the dock says otherwise.

## What macOS calls the run, as against what it calls a window

A run is one process and many windows, so the OS gets one name for all of them, and `main` sets it from
`--title` before the first window: `apple.awt.application.name`. That reaches the menu bar next to the
Apple logo, the app switcher, and every name macOS reports through an API. **It does not reach the
dock** — nothing a process can do reaches the dock, see the next section. Three things about that line
aren't visible from it.

**It is read once, as AWT starts**, and the process registers with macOS under whatever it said then.
Setting it after a window is up changes nothing — measured, not assumed — so it belongs between parsing
the command line and `application { }`, which is the only gap there is.

**It is the same name `-Xdock:name` sets.** That JVM argument only puts the name in an environment
variable AWT reads at that same moment: a run given `-Xdock:name=X` and a run that sets the property to
`X` in `main` produce LaunchServices records differing in nothing but their audit token and check-in
time. So the run task passes no name and an IDE run configuration needs none either, and
`java.awt.Taskbar` is no help — its API is icon, badge, menu and progress, and no name.

**A packaged app ignores the property and keeps its bundle's name.** The `.app` launched with
`--title="Packaged with a title"` logs that title and is still called whatever `packageName` made the
bundle, because jpackage gives it a real bundle. A run from Gradle has no bundle of its own — it is `/…/bin/java`,
bundle id `net.java.openjdk.java` — which is why it is called after whatever launched it until
something names it.

**And a bundle has three names, from three different places**, which is why one of them being right is no
evidence about the others. Measured on a copy of the packaged app renamed and re-plisted a key at a time:

| What a person sees | Where it comes from |
| --- | --- |
| The dock tile | The **file name** of the `.app`, and nothing else — see the next section. |
| The menu bar next to the Apple logo | `CFBundleDisplayName`, else `CFBundleName`. |
| `lsappinfo`, the app switcher | `CFBundleExecutable` — so it says `Shark_Dive` for a renamed copy whose other two say the title. |

So a packaged app can be renamed for a person to navigate by without being rebuilt: `cp -Rc` the app image
(a clone, 80 ms and no disk on APFS), rename the `.app`, set those two plist keys.
`shark-dive-agent/harness/start-harness.sh` does exactly that, and **copies out of `build/compose`
first** — another Compose task deletes that app image, and a window whose bundle was deleted under it dies
the way a window launched from source does.

## The dock only reads a bundle's file name, so `runNamed` gives it one

`-Xdock:name` has not named the dock since around macOS 10.9 — [JDK-8173753][dock-bug], still open,
where the reported symptom is exactly what you get: the name reaches the menu bar and the dock goes on
saying `java`. Confirmed here, three Shark Dive runs whose LaunchServices, `NSRunningApplication` and
WindowServer names were all different showed three dock tiles all called `java`. **So don't spend time
looking for the property or the API call that fixes this. There isn't one.**

What the dock reads is the file name of the bundle a process was launched from. Not `CFBundleName`:
two bundles carrying the same `CFBundleName` and differing only in file name are two differently named
tiles.

`runNamed` is `run` with a bundle around it, generated per launch and named after `--title`:

```bash
./gradlew :shark:shark-dive:shark-dive-app:runNamed \
  --args="--title=\"Hover previews\" shark/shark-android/src/test/resources/compose_leak.hprof"
```

- **It is a launcher script and an `Info.plist` around the classes `run` would have run**, not a
  `jpackage` build. Packaging is a minute of jlink per code change, which would be a minute per look;
  this is a compile.
- **The script `exec`s the JVM** rather than starting it as a child. The JVM has to end up being the
  process macOS launched from the bundle or it is a process of its own again, and the dock is back to
  calling it java.
- **`open` gives it no terminal**, so stdout goes to `build/named/<title>.out` — which is where a run
  that died before it could open a log file says why. Everything after that is in the usual place, see
  the logging section.
- **Relaunching a title while a window of that title is open** is the one thing to avoid: the bundle is
  rewritten in place, and that window is reading it.

**And a Gradle build of any kind kills every Shark Dive window already open.** `run` and `runNamed` both
put the module jars on the classpath rather than a copy of them, and a JVM reads a jar's index once and
then trusts it, so recompiling under a live window makes every class that window hasn't happened to load
yet disappear. What that looks like is the window dying on the next pointer move with
`NoClassDefFoundError: shark/dive/TreemapPoint`, a `ClassNotFoundException` under it, and a stack
trace through code nobody has touched — the class is in the source and in the jar, which is exactly what
makes it read like a real bug in whatever was being worked on. Measured: a window launched at 16:42, a
`shark-dive-core:check` rewriting that jar at 16:46, and the first hover after it gone. So **launch
the window you are handing over last**, after everything that builds — and when one dies this way, check
the jar's mtime against the process start before believing the stack trace.

[dock-bug]: https://bugs.openjdk.org/browse/JDK-8173753

## Reading these names without being able to see the screen

```bash
lsappinfo find pid=<pid>            # ASN:0x0-0x2338336-"Hover previews":
```

`NSRunningApplication.localizedName`, which the app switcher shows, and `kCGWindowOwnerName`, which the
WindowServer holds, agree with it and are readable from `osascript -l JavaScript` through `ObjC.import`.
None of them is what the dock displays, which is why all three can say one thing and the tile another.

The dock tile names, and an app's menu bar, are readable **only with the Accessibility permission**, and
they are the ones worth reading, since they are what someone looking at the screen sees:

```bash
osascript -e 'tell application "System Events" to tell process "Dock" \
  to get name of UI elements of list 1'
osascript -e 'tell application "System Events" to tell process "<the run>" \
  to get name of menu bar items of menu bar 1'      # Apple, <the run>
```

Without that permission both fail with `-1719 not allowed assistive access`, and the only way to know
what the dock says is to ask the person in front of it. It is granted per responsible process — for an
agent, whichever app launched the session — in System Settings → Privacy & Security → Accessibility.
Screen Recording is separate, and without it a screenshot of another process comes back as wallpaper.

**A tile's icon, though, only a screenshot of the dock will tell you.** `NSWorkspace.iconForFile` on a
bundle and `NSRunningApplication.icon` on a pid both hand back a PNG an agent can open, but both read
the LaunchServices record rather than the tile, so both are wrong the moment AWT overwrites it — see
the `-Xdock:icon` section. With Screen Recording granted, this is the picture that settles it:

```bash
# The dock has no window while it is hidden, so a capture of where AX says the tile is comes back blank.
# Post mouse moves down to the bottom edge — one warp isn't enough, it takes an approach and a dwell —
# then ask AX for the tile again: a y that has moved up by the dock's height means it is on screen.
osascript -e 'tell application "System Events" to tell process "Dock" \
  to get {position, size} of (first UI element of list 1 whose name is "<the run>")'
screencapture -x -R <x>,<y>,<w>,<h> tile.png
```

Put the cursor back where it was afterwards, since it is someone's cursor.

## Build and test

```bash
./gradlew :shark:shark-dive:shark-dive-core:test
./gradlew :shark:shark-dive:shark-dive-jdwp:test
./gradlew :shark:shark-dive:shark-dive-app:test   # UI tests, headless, no emulator
./gradlew :shark:shark-dive:shark-dive-app:check   # test + detekt

# Launch it. Paths are optional; without one, use the "Open heap dump…" button. One window per path,
# and one per heap dump opened from the button — see `notes/decisions.md`.
./gradlew :shark:shark-dive:shark-dive-app:run \
  --args="--title=\"Hover previews\" shark/shark-android/src/test/resources/compose_leak.hprof"
```

The repo has real Android heap dumps to try it on: `shark/shark-android/src/test/resources/*.hprof`
and `leakcanary/leakcanary-android-instrumentation/src/androidTest/assets/large-dump.hprof` (39 MB,
the biggest one). All of them are from API 25 or earlier, so every bitmap in them carries its pixels —
anything about a modern dump has to be tried on one taken off a device. See `notes/bitmaps.md`.

**Always pass `--title`, and name the run after the piece of work it is for.** Several Shark Dive windows end up
open at once — one per task, often on the same heap dump — and a name is all the OS gives you to tell
them apart. `--title` goes in front of the heap dump name in every window of that run, including windows
opened from it later, so that two identical `large-dump.hprof` windows never end up on screen.
`DiveArguments` is the whole command line, and it is strict: an unknown option is a message saying
what to type, not a heap dump that can't be found.

**`run` while you work, `runNamed` when you hand a window over.** They take the same command line.
`run` streams the log to the terminal and is a compile away, so it is the one for trying your own
change — don't reach for `runNamed` for that. When the change is done and the app is being started for
someone else to look at, use `runNamed`: it is the only one of the two the dock will name, and with
several Shark Dive windows open the dock is what they navigate by. See the dock section above.

`check` runs detekt (config at `config/detekt-config.yml`); CI and the pre-push hook both enforce
it, so run it before pushing.

Anything that reaches a device — taking a heap dump, fetching bitmaps — can be tried for real with an
emulator running and `leakcanary-android-sample` installed on it
(`ANDROID_SERIAL=emulator-5554 ./gradlew :samples:leakcanary-android-sample:installDebug`). An emulator
older than API 35 is what exercises `shark-dive-jdwp`, since a newer one is asked through a heap dump
instead.

**Two things let a process be dumped, and either is enough**: an app built debuggable, or a device whose
whole build is — `ro.debuggable=1`, which is what a `userdebug` or `eng` image sets and what
`ActivityManagerService.enforceDebuggable` skips its check on. So "only a debuggable app can be dumped"
is right for a phone and wrong for a `userdebug` emulator, where every process on the device can be
dumped and attached to. Measured on two emulators here: an API 36 `user` image refuses
`am dumpheap` of `com.android.systemui` with `SecurityException: Process not debuggable` and lists one
pid under `adb jdwp`; an API 29 `userdebug` one writes 16 MB of `com.android.permissioncontroller` and
lists twenty. `AndroidDevice.dumpsAnyProcess` is that property, read from the `getprop` Shark Dive
already runs.

**A modern emulator image is a `user` build**, so being an emulator is not what makes a device
permissive — check `ro.debuggable` rather than assuming.

**None of that is covered by a test**, and it can't be: a JDI client talks to a real VM or to nothing.
Drive it from a throwaway test against a running emulator, read the numbers, and delete the test — the
numbers belong in `notes/bitmaps.md`.

## Testing conventions

- **UI tests are headless JVM tests**, not instrumentation tests. They live in `src/test/` and use
  `androidx.compose.ui.test.v2.runComposeUiTest`. Import from the **`.v2` package** — the non-v2
  `runComposeUiTest` is deprecated.
- **A test of the whole window runs at the size a window opens at**, which is what `diveUiTest` is
  for. The default test window is smaller, and there the panes beside the view squeeze the controls above
  it to zero width, so a test would be pressing a window nobody has.
- **Hovering takes two moves.** A view describes what the pointer *moved* onto and ignores the enter that
  comes with a pointer arriving, so a single injected `moveTo` reports nothing hovered. `hover()` in
  `DiveUiTest.kt` moves twice; `notes/decisions.md` says why the views read events that way.
- **An injected scroll only lands after a `waitForIdle()`.** `performMouseInput { scroll(n) }` on the
  stack does scroll it, but the offset is still 0 in the same breath, because the scroll is animated and
  the frame hasn't run — so reading it, or the callback it fires, right after the injection says nothing
  happened. Which reads exactly like a wheel a headless test can't deliver, and cost an afternoon of
  looking for one. **How far one notch scrolls is the platform's**, ten pixels with no AWT wheel event
  behind the pointer event to say otherwise, so a test scrolls by notches and reads the pixels back off
  `SemanticsProperties.VerticalScrollAxisRange` rather than asserting a number of its own.
- **Each shape draws into a single `Canvas`, so there are no per-cell semantics nodes.** UI
  tests can't find cells by tag, and **not by label either** — a cell's label is painted text, so no
  assertion and no wait can reach it. Test layout and hit testing as pure functions in
  `shark-dive-core`, and have UI tests drive coordinates with `performMouseInput` and assert on what
  is written outside the view: the chain pane and the details panel either side of it, and the card that
  follows the pointer, whose text is real text and so can be found and its bounds read.
- **A block naming an object is one semantics node**, because `Modifier.openable` merges its descendants,
  so a step of the chain is found by any one of the three lines it prints. The same name is usually in the
  window several times at once — a step of the chain, a tab, a button on the bar, the details panel — so
  `onNodeWithText` failing with "found 2" is that, not a duplicated composable. **Tell them apart by the
  role, not by `hasClickAction()`**, since all of them have one: `Role.Button` is the bar, `Role.Tab` is
  the strip, and no role at all is a row that navigates, which is a link rather than a button. The
  `isTab()` and `isButton()` matchers in the test files are that, and `openable` deliberately sets no role
  so that the third case exists.
- **A UI test knows the map is drawn through `waitForTheTree`**, which waits for the view's
  `contentDescription` with nothing left spinning, because the drawn map itself adds no text to the
  window. Where "the map *moved*" is the point rather than "the map is there", wait on the log line
  every layout writes instead — `DiveAppTest.waitUntilZoomedIn`.
- **A headless test can write a PNG of what Skia drew**, which is how an agent gets to look at this
  UI at all: `onRoot().captureToImage().toAwtImage()` and `ImageIO.write`, after a
  `performMouseInput`, renders the hover highlight and the path bar the same as a real window does.
  Nothing outside the JVM can do this — macOS shows a process that lacks Screen Recording only the
  desktop picture and its own windows, so a screenshot of a `./gradlew run` taken from any other
  process comes back as wallpaper. Delete the capture again once it has been looked at; it's
  scaffolding, not a test.
- **The UI tests record `SharkLog` for every test**, not only for the ones asserting on it. A log
  line is built from state — an index into a path, a node id — so a line built from the wrong state
  should fail the test that reaches it rather than wait for a session nobody can read. The `RecordedLog`
  rule does the recording, and is a rule rather than a `@Before` because putting the logger back is the
  part that isn't optional: a test that leaves `SharkLog.logger` set breaks every test after it.
- **What the log says is also how often something happened.** Every read of the heap dump is one line
  through `HeapDumpSession.read`, so counting them is what holds the window to laying the tree out once
  per view asked for, which is what `TreeLayoutTest` does. Those counts are sound because reads queue on
  that one thread in order: anything queued behind the read a test waited for is already logged by then.
- **A `Window` needs a display, so nothing inside `application { }` is covered headless.** Which
  window a heap dump opens in is plain state in `DiveWindow.kt`, unit tested by
  `DiveWindowTest`. `DiveApp` is one window's worth of app and takes the heap dump it shows
  as a parameter, so a UI test drives one window and nothing else.
- **A click is a fraction of the view, never of the window.** `DiveAppTest.viewBounds` measures the
  view by its `contentDescription`, and every press helper is relative to that. Window fractions break
  the moment anything above the view changes height, which is a change to the top bar away.
- **A test about `java.lang.ref` strengths needs a dump of a real JVM, taken without collecting first.**
  `JvmReferenceStrengthTest` writes one with `HotSpotDiagnosticMXBean.dumpHeap(path, live = false)`,
  because the collection a heap dump normally begins with clears a weak referent nothing else holds and,
  since JDK 9, a phantom one — so the strengths the test is about would be missing from a dump taken the
  usual way. That leaves it fragile in a way its KDoc explains: anything allocated between its
  `System.gc()` and the dump can trigger a collection that clears the lot. It doesn't replace the
  `dump { }` cases either, ART's reference classes and the lists it keeps them on not being HotSpot's.
- Build test heap dumps with the `hprofFile.dump { }` DSL from `shark-hprof-test` rather than
  checking in binary fixtures or hand-writing hprof bytes. A dump with bitmaps in it is `BitmapDumps.kt`
  in `shark-dive-core`'s tests — the `"a.b.C" instance { }` shorthand declares a class per instance,
  so two bitmaps built that way are two `android.graphics.Bitmap` classes, which no real dump has.
- **A UI test that opens a note must pass an `DiveNotes` over a temporary directory.** `DiveApp`'s
  default keeps notes in `~/.shark-dive/notes`, so a test taking it writes into the notes of whoever is
  running it. A window that never opens one only lists that directory to see which tabs to mark, which is
  why the tests that don't touch notes need no directory of their own.
- **And a UI test that stars an object must pass a `DiveStars` over a temporary directory**, for the same
  reason: the default keeps them in `~/.shark-dive/starred`, so a test that stars puts an address into the
  working set of whoever is running it. Reading is harmless — a test window reads the file for its own dump
  and finds nothing — so only the tests that click a star need one.
- **A UI test that opens the *Agent logs* screen must pass its own `agentSessions`.** The default reads
  `~/.shark-dive/agents/sessions`, so a test taking it draws whatever the person running it last handed
  to an agent — and asserts on rows it didn't write. `AgentLogsScreenTest` builds the sessions it expects.
- **A UI test must pass a `DeviceHeapDumps` built on a fake `Adb`.** `DiveApp`'s default shells out to
  the machine's `adb`, so a test that takes it has whatever device is plugged in to answer for — and the
  window can dump the heap of a real process. `FakeAdb` matches command prefixes, because the remote dump
  path contains a timestamp.
- **A synthetic Android class needs the fields the object inspectors read.** `HeapObjectSummary` runs
  `AndroidObjectInspectors`, and those read fields with `!!` — an `android.view.View` without `mParent`,
  `mWindowAttachCount`, `mAttachInfo` and `mContext` makes `summarize()` throw a bare
  `NullPointerException` from inside shark-android, which reads like a bug in Shark Dive.

## Notes

Design decisions and findings, kept current as the work proceeds:

- `notes/decisions.md` — stack and structure decisions, with rationale
- `notes/dominator-tree.md` — dominator algorithm findings, memory/perf numbers
- `notes/referrer-index.md` — how the referrer index is encoded, what it holds, and why the order it hands
  referrers back in is not free to change
- `notes/treemap-rendering.md` — adaptive depth model, the two shapes, bugs in the existing Android
  treemap
- `notes/bitmaps.md` — which Android versions put a bitmap's pixels in the heap dump, and the two ways
  the ones that don't are fetched off the device
- `notes/dependency-injection.md` — what Dagger and Metro leave in a heap dump, why the owner rule is
  about the provider rather than the component, and how to dump really generated code
- `notes/agent-surface.md` — what the MCP surface costs a client in tokens, measured, and why a CLI and a
  skill are adapters over the same registry rather than second implementations
- `notes/agent-eval.md` — how well an agent solves a leak, scored with no model doing the scoring: the answer
  keys, the three ways a run gets handed its own answer, and the baseline to beat

Update these in the same change that makes them stale. They're for agents, so keep them short and
skip anything derivable from the code.
