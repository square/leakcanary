# Android Studio's LeakCanary task, and bringing all of it into Shark Explorer

Android Studio now ships a Profiler task called **"Find Memory Leaks with LeakCanary"**
(`ProfilerTaskType.LEAKCANARY`), which watches a running app's retained objects, dumps its heap, runs
**Shark on the developer's machine**, and shows the leak traces in the IDE. It is a second front end for
this repo's own analysis, built by people who could not change this repo, and it is the closest thing
there is to a specification of what a heap-leak UI is expected to do.

This file is the scope of what it supports, and the plan for supporting all of it here. It exists
because none of Studio's source is in this repo and reading it took a day: it is worth writing down
once so the next agent starts from the feature list rather than from `git clone`.

## Where Studio's code is

Two AOSP repos, both `git clone --filter=blob:none --depth 1` and then `git sparse-checkout set`. Note
that `platform/tools/base`'s branch is `mirror-goog-studio-main`, not `main`, and that Gitiles refuses
`?format=TEXT` for `.md` files — so clone, don't scrape.

| Path | What's in it |
| --- | --- |
| `tools/base/studio-leakcanary/` | An AAR Studio adds to the app's build, `com.android.tools.studio.leakcanary:leakcanary`. Reaches LeakCanary by reflection and answers broadcasts. |
| `tools/base/profiler/app/common/…/profilers/LeakCanaryManager.java` | The perfa (JVMTI agent) side, called from native over JNI. Sends the broadcasts the AAR answers. |
| `tools/base/transport/proto/` | `leakcanary_data.proto`, plus LeakCanary command types 801–809 and event kinds 801–806 in `commands.proto`/`common.proto`. |
| `tools/base/leakcanarylib/` | A parser and data model for **LeakCanary's human-readable text report**, plus the code to print it back out. |
| `tools/adt/idea/profilers/…/leakcanary/` | The host side: `SharkHostAnalyzer`, `LeakCanaryHeapDumper`, `LeakCanaryModel`, `LeakCanaryTaskHandler`, `LeakInsightModel`. |
| `tools/adt/idea/profilers-android/…/commands/` | `LeakCanaryLogcatCommandHandler` (the on-device mode's logcat scraper), `LeakCanaryAnalysisCommandHandler`. |
| `tools/adt/idea/profilers-ui/…/taskbased/tabs/task/leakcanary/` | The Compose UI: leak list, leak details, insight panel, action bar, banner. |

## The closed loop

There are two modes, chosen in the task's run configuration (`LeakCanaryConfiguration`): `STUDIO`
("Use Android Studio Settings", the default) and `NATIVE` ("Use configuration defined in app code").
Internally they are `ON_HOST` and `ON_DEVICE`.

**`ON_HOST` is the interesting one.** LeakCanary is put in a mode where it watches objects and reports
counts but **never dumps or analyses anything itself** — the AAR flips `LeakCanary.config.dumpHeap` to
false by reflection. Then:

1. The AAR's `StudioLeakCanaryListener` installs a dynamic `Proxy` over
   `leakcanary.OnObjectRetainedListener`. On each callback it debounces 100 ms, reads
   `retainedObjectCount`, runs a GC if it is non-zero, reads the count again, and broadcasts
   `studio.leakcanary.OBJECT_COUNT_UPDATE` **only when the number changed**. Then it reschedules
   itself every 5 s while the count is above zero.
2. perfa turns that broadcast into a `LEAKCANARY_OBJECT_COUNT` transport event.
3. `LeakCanaryModel` sees the count reach the threshold and calls `forceHeapDump()`.
4. `LeakCanaryHeapDumper` sends the profiler's own `HEAP_DUMP` command, waits for
   `MEMORY_HEAP_DUMP_STATUS` then `MEMORY_HEAP_DUMP`, and downloads the `.hprof` over
   `Transport.BytesRequest`.
5. `SharkHostAnalyzer` runs `HeapAnalyzer` **on the developer's machine**, against a bundled Shark
   **2.14** (`ON_HOST_SHARK_VERSION = "2.14"`, hardcoded, written into the saved session's metadata).
6. The result is turned into text with `HeapAnalysisSuccess.toString()`, parsed back into
   `leakcanarylib`'s model, sent through the transport pipeline as a `SEND_LEAKCANARY_ANALYSIS` command,
   and comes back as a `LEAKCANARY_ANALYSIS` event that the UI renders.
7. `SIGNAL_HEAP_DUMP_COMPLETE` goes to the app, which calls `clearObjectsWatchedBefore` so the same
   objects aren't reported twice.

**`ON_DEVICE` leaves LeakCanary alone** and scrapes its logcat instead:
`LeakCanaryLogcatCommandHandler` watches the `LeakCanary` and `LeakCanary:Manual` tags, treats
everything between `HEAP ANALYSIS RESULT`/`HEAP ANALYSIS FAILED` and the `====` after `METADATA` as one
report, and — because logcat drops lines — also reassembles **partial** traces, starting at
`bytes retained by leaking objects` or `GC Root` and ending at a `╰→` followed by a de-indent or a
two-second gap, then synthesises a fake complete report with a SHA-256 signature. It also parses
`Found (\d+) objects? retained` and `Analysis in progress, (\d+)% done` for the live count and progress.

## Every feature, in full

### Getting LeakCanary into the app

`IntellijProfilerServices` offers to add the dependency for you: `analyzeDependencyCompatibility`, a
reflection fallback through `RepositoryUrlManager.getLibraryRevision` so preview versions resolve, a
confirmation dialog, `registerDependency`, `requestSyncProject`. If LeakCanary isn't there, the task
refuses with a message naming the exact line —
`debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.14'`.

The AAR it adds declares a signature-level permission `${applicationId}.permission.LEAK_CANARY_INTERNAL`
and a `ContentProvider` with `initOrder="-1"` so it initialises *after* LeakCanary's own installer and
*before* `Application.onCreate()`. It registers four receivers (heap-dump-finished, start-listening,
get-threshold, force-dump), `RECEIVER_NOT_EXPORTED` on API 33 and up and permission-guarded below. Its
bundled `leakcanary-android-2.14.aar` is a **stub**, there only so the real artifact appears in the
generated POM.

### Deciding the app can be profiled

`LeakCanaryTaskHandler` refuses when a debugger is attached, when the process isn't debuggable
(`SupportLevel.Feature.MEMORY_LEAK_WITH_LEAKCANARY`), and for the private-compute uid range. The
presence check attaches the JVMTI agent and sends `GET_LEAKCANARY_THRESHOLD`, waiting up to 7 s:
`threshold > 0` means present, `0` means timeout or absent, and `-1` means the reflection failed, which
the UI attributes to minification. Successes are cached per `"streamId:pid:name"`; failures never are.

### Watching retained objects live

A count and nothing else. The action bar reads
`"N retained objects. Waiting to dump heap until M retained objects."`. The threshold is a dropdown of
`1..10, 15, 20, 25, 30, 35, 40, 45, 50`, default 5, and in `STUDIO` mode it overrides whatever the app
configured. `STOP_LEAKCANARY_OBJECT_COUNT_TRACKING` turns the watching off.

### Dumping the heap

Automatically when the count reaches the threshold, or by the **Force dump** button — which is enabled
only when `0 < count < threshold && !isStopping && !isForceDumpExecuting`, with five distinct tooltips
for the ways it can be disabled. **Stop recording** forces one final dump if anything is still retained.
A recording timer and a 0–100 % analysis progress bar run above the list.

### Analysing it

`SharkHostAnalyzer` is 79 lines and this is all of it:

```kotlin
analyzer.analyze(
  heapDumpFile = hprofFile,
  graph = hprofFile.openHeapGraph(),
  leakingObjectFinder = KeyedWeakReferenceFinder,
  referenceMatchers = AndroidReferenceMatchers.Companion.appDefaults,
  computeRetainedHeapSize = true,
  objectInspectors = AndroidObjectInspectors.Companion.appDefaults,
)
```

Progress is `(step.ordinal + 1) * 100 / Step.entries.size`. Failures become a `HeapAnalysisFailure`, and
`LeakCanaryHeapDumper` distinguishes `SHARK_ANALYSIS_OOM`, `HEAP_DUMP_GENERATION_FAILED`,
`HPROF_DOWNLOAD_FAILED`, `PARSING_FAILURE`, `SHARK_ANALYSIS_EXCEPTION`, `BROADCAST_DELIVERY_FAILED` and
`UNKNOWN_ERROR`. Note there is no `MetadataExtractor`, so an `ON_HOST` analysis carries no metadata
section at all.

### The parsed model, and the text round trip

`leakcanarylib` is a **parser for LeakCanary's printed report**: `Analysis.fromString` dispatches on
`"HEAP ANALYSIS RESULT"` / `"HEAP ANALYSIS FAILED"`, and `AnalysisParser`, `LeakParser`,
`LeakTraceParser`, `NodeParser` and `ReferencingFieldParser` rebuild `Analysis`, `Leak`, `LeakTrace`,
`Node`, `ReferencingField`, `GcRootType`, `LeakingStatus` and `LeakTraceNodeType` from the box-drawing
characters — including the `​` zero-width space LeakCanary prefixes and the `~~~` underline that
marks a likely cause. `toString()` prints it back out again, and that is the form stored in the
transport pipeline and in the saved session.

This exists because `ON_DEVICE` mode only *has* text. `ON_HOST` mode has a real `HeapAnalysisSuccess`
and throws it away: `LeakCanaryParser().parseLogcatMessage(analysisResult.toString())`.

### The leak list

Three columns: **Leak** (a class name derived by `getLeakClassName`, which walks the trace for the first
"suspect" node — one whose status is UNKNOWN, or NO immediately before a non-NO — and prints
`"${referenceField.className}.${referenceField.referenceName}"`), **Occurrences** (`leakTraceCount`) and
**Total leaked** (`retainedByteSize / 1024` KB).

### The leak trace panel

A GC-root node then one expandable node per step. Collapsed, a node prints `className nodeType` and
`(field: NextClass)`, with the last section coloured by leaking status — red for YES, amber `0xFFFFBF00`
for UNKNOWN, grey for NO — and an icon per status. Expanded, it adds **Leaking** (icon and status),
**Why** (`leakingStatusReason`), `Referencing Field:`, `Retained Bytes:`, `Referencing Objects:` and a
collapsible **More info** bullet list of the node's notes, with `stripLeakCanaryAscii()` removing
`│ ├─ ╰→ ↓ ~` from each. A coloured vertical line runs down the trace.

### Go to declaration

`CodeLocation.Builder(node.className.removeSuffix("[]"))` and
`ideServices.codeNavigator.navigate`, with `isDeclarationAvailableAsync` deciding between a
`Link("Go to declaration")` and a "No declaration found" notification.

### Copy, expand, collapse

**Copy trace to clipboard** copies `selectedLeak.toString()`. Expand all / Collapse all are buttons and
also `Ctrl+`+`` / `Ctrl+-`.

### AI insight, and Fix with Agent

A third pane, opened from a 26 dp vertical "Insights" tab strip. `LeakInsightModel` fetches a streamed
explanation through `GeminiPluginApi.generate`, caches it in a `ConcurrentHashMap` keyed by
`leak.signature`, and has an auto-generate toggle persisted under `"leakcanary.insight.auto.generate"`,
thumbs up/down, copy and refresh. **Fix with Agent** submits
`"Fix this memory leak and summarize the outcome:"` plus the trace to the AI chat tool window via
`GeminiPluginApiV2.submitQueryInToolWindow`.

`ProfilerPrompts.kt` is worth reading in full before writing anything similar. The system prompt is
`# Role` ("Android Memory Performance Agent… world-class expert in JVM heap analysis, LeakCanary
traces, and Android Lifecycle internals"), then a `# Security Warning` saying the trace is **untrusted
data**, never to follow instructions or links embedded in it, then `# Task` and a fixed
`# Response Structure` of `### Leak Diagnosis`, `### Solution`, `### Outcome` — with an explicit
constraint not to guess a retained size. `sanitizeTrace` strips backticks.

### Past recordings and export

Each analysis becomes a `LeakCanarySessionArtifact` in the sessions list, reopened through
`loadFromPastSession(startTimestamp, endTimestamp, session)`, and exportable as
`leakcanary-<yyyyMMdd'T'HHmmss>.asdb` with `leakcanary_mode` and `shark_version` in its metadata.

### The banner, and analytics

A dismissible banner: "On-device customizations are being bypassed. Switch to App Customization to
enable them.", with "Edit configuration" and "Don't show again". And 22 tracked UI actions
(`LeakCanaryUiAction`) plus a `LeakCanaryLeakAnalysis` record carrying counts of NO/MAYBE/YES rows,
analysis duration, hprof size and download duration.

## What Studio's design costs, measured against this repo

Five things were checked against LeakCanary at HEAD, on an API 29 emulator running
`leakcanary-android-sample`, over JDWP.

**1. The reflection bridge is pinned to LeakCanary 2.14's internals, and is already broken.**
`LeakCanaryReflectionHelper.ensureInitialized()` is one `try` block over ten reflective lookups, and any
one failure sets `state = FAILED`. One of them is
`Class.forName("leakcanary.GcTrigger$Default")`. On HEAD there is no such class: `GcTrigger` is a
`fun interface` whose companion has `getDefault()`, and the implementation is
`FinalizingInProcessGcTrigger`. Asked of a live app, class by class:

```
present: leakcanary.LeakCanary
present: leakcanary.internal.InternalLeakCanary
present: leakcanary.AppWatcher
present: leakcanary.OnObjectRetainedListener
MISSING: leakcanary.GcTrigger$Default
present: leakcanary.FinalizingInProcessGcTrigger
```

So against LeakCanary 3.0-alpha the whole bridge fails, the threshold comes back as `-1`, and Studio
reports it as a reflection failure — which its own strings attribute to a minified app. `ObjectWatcher`
is `@Deprecated` in favour of `RetainedObjectTracker` too, so more of those ten lookups are on their way
out.

**2. `heapDumpUptimeMillis` is never stamped, so every `ON_HOST` trace reports negative durations.**
`grep -rl heapDumpUptimeMillis` over both AOSP repos matches nothing. LeakCanary sets that static field
right before dumping so the analysis can say how long an object has been retained; a dump taken by
anything else leaves it at 0. Measured on the same app, dumped with `am dumpheap` and analysed:

```
watchDurationMillis = -319268
retainedDurationMillis = -324276
```

Writing the field over JDWP before the dump — one `ClassType.setValue` — fixes it:

```
stamping heapDumpUptimeMillis = 570193
watchDurationMillis = 250925
retainedDurationMillis = 245917
```

**3. The retained sizes are from the analysis's own approximation, not from an exact dominator tree.**
`computeRetainedHeapSize = true` makes `PrioritizingShortestPathFinder` run a second traversal to
attribute sizes; the explorer has already built an exact `HeapDominatorTree` by the time anything is
analysed. On `compose_leak.hprof` the analysis reports 17,821 bytes retained for the one leak it finds,
while the exact tree gives the three `KeyedWeakReference` referents 233 B, 79,345 B and 4,072 B. On
`large-dump.hprof` the two agree to within 578 bytes. So the disagreement is real and it is not a
constant factor.

**4. The live signal is a bare integer.** `LEAKCANARY_OBJECT_COUNT` carries a count, so the UI can say
"2 retained objects" and nothing about *what* they are. Everything needed to name them is readable from
the same process:

```
getRetainedWeakReferences: 2
  com.example.leakcanary.MainActivity — "…received Activity#onDestroy() callback" key=80b9ae91-… watched=543945 retained=548948
  com.example.leakcanary.MainActivity — "…received Activity#onDestroy() callback" key=ec16f125-… watched=319268 retained=324276
```

**5. A `HeapAnalysisSuccess` is printed to text and parsed back.** Every field
`leakcanarylib`'s five parsers reconstruct is already a field of `LeakTrace`, `LeakTraceObject` and
`LeakTraceReference`. `ON_DEVICE` mode needs the parser; `ON_HOST` mode has the objects.

## The plan

The through-line: **the explorer already has the two expensive things Studio had to build a pipeline
for** — an open, indexed `HeapGraph` with an exact dominator tree, and a debugger connection to the live
process. So each of Studio's features becomes a small addition rather than a subsystem, and none of it
needs a dependency added to the app being debugged.

What that replaces, concretely: no AAR, no stub POM, no signature permission, no `ContentProvider`, no
JVMTI agent, no broadcasts, no gRPC transport, no protos, no Gradle sync, no reflection bridge, and no
text round trip. `shark-explorer-jdwp` already attaches to a live app for bitmaps and for GC, and
`JdwpSession` already solves the two hard parts of doing so (ART refuses `invokeMethod` on a thread it
suspended itself, so a method-entry event with a count filter of 1 is used, and an idle app is nudged
into running code with `dumpsys meminfo`).

### Phase 1 — leaks in a dump that's already open

`HeapLeaks.kt` in `shark-explorer-core`: run `HeapAnalyzer` against the graph `HeapExplorer` already
holds, with `KeyedWeakReferenceFinder`, `AndroidReferenceMatchers.appDefaults`,
`AndroidObjectInspectors.appDefaults` and `AndroidMetadataExtractor` — but
**`computeRetainedHeapSize = false`**, taking each leaking object's retained size and count from the
exact tree instead (finding 3). Runs through `HeapDumpSession.read` like every other read, so it is
cancellable and logged, and progress reaches the UI as the step's `humanReadableName` rather than a
percentage.

This alone gives Studio's list and trace panel on any dump the explorer can open, including ones taken
by LeakCanary itself, with no device involved.

### Phase 2 — the leak list and the trace panel

A fourth `ExplorerScreen` — `Leaks` — beside `Tree`, `Objects` and `Starred`, so it carries
`treeNavigation` and `describedNode` like the others and clicking a node in a trace goes to it in the
treemap. That is the one thing Studio structurally cannot do: its leak trace and its heap have no
relationship.

The list is Studio's three columns, with **Total leaked** from the exact tree. The trace panel is
Studio's: a GC-root header, one expandable node per step, red/amber/grey by `LeakingStatus`, and the
same expanded fields. Rendered from `LeakTrace` directly (finding 5). "Go to declaration" has no IDE to
call, so it becomes **"Show in the map"** plus the existing star, which is the useful half of it here.
Expand all / collapse all with `Ctrl+`+`` / `Ctrl+-`, and **Copy trace** putting `leak.toString()` on
the clipboard, both as Studio has them.

Layout and status colouring go in core as pure functions, per the module rules, so they can be tested
without a window.

### Phase 3 — watching a live app

`JdwpRetainedObjects` in `shark-explorer-jdwp`, reading over JDI what the AAR reads by reflection:

| What | How |
| --- | --- |
| retained and tracked counts | `AppWatcher.INSTANCE.getObjectWatcher()` then `getRetainedObjectCount()` / `getTrackedObjectCount()` |
| the configured threshold | `LeakCanary.INSTANCE.getConfig().getRetainedVisibleThreshold()` |
| whether LeakCanary would dump | the same config's `getDumpHeap()` |
| **what** is retained | the `retainedObjectTracker` field, then `getRetainedWeakReferences()`, then each reference's `description`, `key`, `watchUptimeMillis`, `retainedUptimeMillis` and its `referent` **field** — a field read runs no code in the app, where `get()` would promote the referent back to strongly reachable |
| a collection, then a recount | `Runtime.getRuntime().gc()` etc., which `JdwpGc` already does |

All verified against a live app. Two JDI details the code has to keep: anything returned from an invoke
and read later needs `disableCollection()` — a `toArray()` result was collected between the invoke and
`length()`, since invokes run with `RESUME_OTHER_THREADS` — and every invoke stays without
`INVOKE_SINGLE_THREADED` for the reasons `JdwpSession` already documents.

**Where this lands in the UI, and what it must not become.** A JDWP session suspends the app, so it is
not something to hold open while a count ticks. So: the *dialog* that already picks a device and a
process gains a line per process saying what it is retaining right now, read in one short session; and
a window opened on a dump gains a "check again" beside the process it came from. No polling loop, no
5-second timer, and no mode flag threaded through the explorer.

Being able to read the app's own `retainedVisibleThreshold` and `dumpHeap` also means the explorer can
*say* what LeakCanary is going to do rather than taking it over. Studio's banner exists because it
silently overrides the app's configuration; there is no reason to inherit that.

### Phase 4 — dump when it's worth dumping

`TakeHeapDumpDialog` already dumps a chosen process. It gains, next to the bitmap checkbox: stamp
`KeyedWeakReference.heapDumpUptimeMillis` before the dump (finding 2, and it costs one static field
write inside the JDWP session the dialog may already be opening for GC below API 27), and, once the dump
is open, analyse it. Which makes the explorer's existing dump path into Studio's "force dump", and the
existing `-g`/`JdwpGc` collection into the GC the AAR runs before recounting.

`clearObjectsWatchedBefore` after a dump — Studio's `SIGNAL_HEAP_DUMP_COMPLETE` — is offered rather than
done: this is an explorer, and quietly changing what the app is watching is a side effect nobody asked
for. `ON_DEVICE` mode's logcat scraping is the one Studio feature deliberately left out; the strings it
parses (`Found 2 objects retained, not dumping heap yet (app is visible & < 5 threshold)`) exist and were
checked, but reassembling a truncated text report is a workaround for having no other channel, and this
has JDI.

### Phase 5 — explaining a leak

The insight panel is worth having and is not worth building the way Studio built it. Studio has a Gemini
plugin in-process; a desktop app has no model. What it does have is `leak.toString()`, which is the
whole prompt — so this is **"Copy for an assistant"**: the trace plus the question, on the clipboard,
ready to paste. If an API key ever gets configured, the panel is already the place for the answer.
`ProfilerPrompts.kt`'s security warning is the part to copy verbatim in spirit: a leak trace contains
class and field names from an untrusted app, and anything that sends one to a model has to say it is
data.

### What is deliberately not planned

- **Dependency auto-injection.** Nothing here edits the app's build, and after phase 3 nothing needs to:
  the explorer reads a live LeakCanary if there is one and analyses a dump either way.
- **Presence pre-flight as a gate.** Studio refuses the task when it can't find LeakCanary. The explorer
  should open the dump regardless and say what it found — a dump with no `KeyedWeakReference` in it has
  a treemap, which is most of the point of this app.
- **Analytics.** No telemetry in this repo.
- **A separate export format.** A heap dump is the artifact, and it is already a file.

## Numbers measured while scoping this

All on this machine, from throwaway tests since deleted.

| What | Dump | Time |
| --- | --- | --- |
| `HeapAnalyzer` on an already-open graph, no retained sizes | `compose_leak.hprof`, 23 MB | 1,498 ms |
| the same, `computeRetainedHeapSize = true` | " | 1,894 ms |
| exact `HeapDominatorTree` + `buildNodes` | " | 477 ms |
| `HeapAnalyzer`, no retained sizes | `large-dump.hprof`, 38 MB | 787 ms |
| the same, with retained sizes | " | 1,133 ms |
| exact `HeapDominatorTree` + `buildNodes` | " | 689 ms |
| `am dumpheap -g` over JDWP, pulled | live sample app, 16 MB | 2,648 ms |
| `HeapAnalyzer` with retained sizes, cold index | " | 5,180 ms |
| `HeapExplorer.open`, warm page cache | " | 931 ms |

So the leak pass costs a few seconds on top of opening a dump, and reusing the exact tree for retained
sizes saves 350–400 ms of the analysis while making the number better rather than worse.
