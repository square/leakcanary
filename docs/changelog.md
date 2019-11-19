# Change Log

## Version 2.0 Beta 4 (2019-11-18)

LeakCanary 2 is in **beta**: the internals and APIs are mostly stable.

Now is a great time to adopt it and provide feedback before the stable release. We're counting on you to find bugs and suggest improvements! Check out the new [Getting Started](https://square.github.io/leakcanary/getting_started) instructions and the [migration guide](https://square.github.io/leakcanary/upgrading-to-leakcanary-2.0/). 

* Improved string rendering for heap analysis results
* UX redesign [#1445](https://github.com/square/leakcanary/issues/1445)
* Support for pattern matching of native reference leaks [#1562](https://github.com/square/leakcanary/issues/1562)
* Added support for deobfuscation using Proguard mapping files in Shark [#1499](https://github.com/square/leakcanary/issues/1499). This isn't directly supported in LeakCanary yet.
* Added support for extracting metadata from the heap dump (see the [recipe](recipes.md#extracting-metadata-from-the-heap-dump)) [#1519](https://github.com/square/leakcanary/issues/1519)
* Improved auto disabling of LeakCanary in Unit and UI tests [#1552](https://github.com/square/leakcanary/issues/1552)
* Several performance improvements when parsing heap dumps
* Fixed several bugs and crashes
* Added new known leak patterns

Many thanks to
[@Armaxis](https://github.com/Armaxis),
[@BraisGabin](https://github.com/BraisGabin),
[@bric3](https://github.com/bric3),
[@elihart](https://github.com/elihart),
[@fernandospr](https://github.com/fernandospr),
[@flickator](https://github.com/flickator),
[@gabrysgab](https://github.com/gabrysgab),
[@JorgeDLS](https://github.com/JorgeDLS),
[@lannyf77](https://github.com/lannyf77),
[@msfjarvis](https://github.com/msfjarvis),
[@mzgreen](https://github.com/mzgreen),
[@ozmium](https://github.com/ozmium),
[@PaulWoitaschek](https://github.com/pyricau),
[@pyricau](https://github.com/pyricau),
[@shelpy](https://github.com/shelpy),
[@vRallev](https://github.com/vRallev),
[@ZacSweers](https://github.com/ZacSweers)
for the contributions, bug reports and feature requests.

For more details, see the [2.0-beta-4 Milestone](https://github.com/square/leakcanary/milestone/12) and the [full diff](https://github.com/square/leakcanary/compare/v2.0-beta-3...v2.0-beta-4).

## Version 2.0 Beta 3 (2019-08-22)

* Baseline memory usage for large hprofs divided by 3 and removed memory spikes [#1543](https://github.com/square/leakcanary/pull/1543)
* Fixed crash when LeakCanary is initialized from another process [#1529](https://github.com/square/leakcanary/issues/1529)
* Java local references are deprioritized to look for longer alternative paths [#1525](https://github.com/square/leakcanary/pull/1525)
* Fixed `JavaLocalPattern` not matching on Lollipop [#1524](https://github.com/square/leakcanary/pull/1524)

Many thanks to
[@Armaxis](https://github.com/Armaxis),
[@elihart](https://github.com/elihart),
[@emartynov](https://github.com/emartynov),
[@hmcgreevy-instil](https://github.com/hmcgreevy-instil),
[@pyricau](https://github.com/pyricau)
for the contributions, bug reports and feature requests.

For more details, see the [2.0-beta-3 Milestone](https://github.com/square/leakcanary/milestone/11) and the [full diff](https://github.com/square/leakcanary/compare/v2.0-beta-2...v2.0-beta-3).

## Version 2.0 Beta 2 (2019-08-02)

* Fixed *Leak analysis failed: Object id not found in heap dump.* [#1516](https://github.com/square/leakcanary/issues/1516)
* 10x speed increase of hprof indexing on large heap dumps [#1520](https://github.com/square/leakcanary/pull/1520)

Many thanks to
[@kolphi](https://github.com/kolphi),
[@pyricau](https://github.com/pyricau),
[@ZacSweers](https://github.com/ZacSweers)
for the contributions, bug reports and feature requests.

For more details, see the [2.0-beta-2 Milestone](https://github.com/square/leakcanary/milestone/10) and the [full diff](https://github.com/square/leakcanary/compare/v2.0-beta-1...v2.0-beta-2).

## Version 2.0 Beta 1 (2019-07-30)

* New standalone library! [Shark](shark.md) is the heap analyzer that powers LeakCanary 2, and it can run in any Java VM. It comes with a [CLI](shark.md#shark-cli): you can now run `shark-cli analyze-process com.example.myapp` from your computer.
* New Heap Explorer directly on device! Open a Heap Analysis in LeakCanary, tap the options menu and select "Heap Explorer". This is still experimental and not very user friendly, contributions welcome!
* **Large API rewrite** to improve usability. If you used the alpha with a customized configuration, there are breaking changes. Of note: LeakSentry became [AppWatcher](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/), RefWatcher became [ObjectWatcher](/leakcanary/api/leakcanary-object-watcher/leakcanary/-object-watcher/), AndroidExcludedRefs became [AndroidReferenceMatchers](/leakcanary/api/shark-android/shark/-android-reference-matchers/), AnalysisResultListener became [OnHeapAnalyzedListener](/leakcanary/api/leakcanary-android-core/leakcanary/-on-heap-analyzed-listener/), AndroidLeakTraceInspectors became [AndroidObjectInspectors](/leakcanary/api/shark-android/shark/-android-object-inspectors/).
* The entire API surface is now documented and the documentation is available on this website: see the **LeakCanary API** tab at the top.
* Removed the **dependency on Android X**. No more configuration issues! [#1462](https://github.com/square/leakcanary/issues/1462) 
* Added **Proguard rules** for LeakCanary and ObjectWatcher. [#1500](https://github.com/square/leakcanary/pull/1500) 
* Display LeakCanary version in the About screen. [#1448](https://github.com/square/leakcanary/issues/1448) 
* Bug fixes, new reference matchers and object inspectors

Many thanks to
[@arctouch-carlosottoboni](https://github.com/arctouch-carlosottoboni),
[@jemaystermind](https://github.com/jemaystermind),
[@kushagrakumar27](https://github.com/kushagrakumar27),
[@pyricau](https://github.com/pyricau),
[@snkashis](https://github.com/snkashis)
for the contributions, bug reports and feature requests.

For more details, see the [2.0-beta-1 Milestone](https://github.com/square/leakcanary/milestone/9) and the [full diff](https://github.com/square/leakcanary/compare/v2.0-alpha-3...v2.0-beta-1).

## Version 2.0 Alpha 3 (2019-07-04)

* [#1401](https://github.com/square/leakcanary/pull/1401) LeakCanary can now import all hprof files created from prior LeakCanary versions.
* [#1414](https://github.com/square/leakcanary/pull/1414) New API: `RefWatcher.retainedInstances` which returns the instances that are currently considered retained.
* [#1419](https://github.com/square/leakcanary/pull/1419) New APIs: `LeakCanary.Config.maxStoredHeapDumps` (default 7) and `LeakCanary.Config.requestWriteExternalStoragePermission` (default false). LeakCanary won't ask for the external storage permission anymore by default.
* [#1338](https://github.com/square/leakcanary/issues/1338) API change: `LeakCanary.Config.exclusionsFactory` replaced with `LeakCanary.Config.knownReferences` (simpler use), `LeakCanary.Config.leakInspectors` and `LeakCanary.Config.labelers` merged into `LeakCanary.Config.leakTraceInspectors` which provides access to the entire leak trace as well as a new graph oriented API that replaces the low level hprof parser API.
* [#1382](https://github.com/square/leakcanary/issues/1382) LeakCanary now disables automatic heap dumping when running in AndroidX UI tests.
* [#1424](https://github.com/square/leakcanary/pull/1424) API rename: `RefWatcher.hasRetainedReferences` => `RefWatcher.hasRetainedInstances`, `RefWatcher.retainedReferenceCount` => `RefWatcher.retainedInstanceCount`, `RefWatcher.hasWatchedReferences` => `RefWatcher.hasWatchedInstances`, `RefWatcher.removeKeysRetainedBeforeHeapDump` => `RefWatcher.removeInstancesRetainedBeforeHeapDump`, `RefWatcher.clearWatchedReferences` => `RefWatcher.clearWatchedInstances`.
* [#1432](https://github.com/square/leakcanary/pull/1432) [#1438](https://github.com/square/leakcanary/pull/1438) [#1440](https://github.com/square/leakcanary/pull/1440) New "won't fix" leaks and leak trace inspectors
* [#1374](https://github.com/square/leakcanary/issues/1374) [#1364](https://github.com/square/leakcanary/issues/1364) [#1366](https://github.com/square/leakcanary/issues/1366) [#1417](https://github.com/square/leakcanary/issues/1417) [#1399](https://github.com/square/leakcanary/issues/#1399) [#1416](https://github.com/square/leakcanary/issues/1416) [#1407](https://github.com/square/leakcanary/issues/1407) [#1427](https://github.com/square/leakcanary/issues/1427) [#1385](https://github.com/square/leakcanary/issues/1385) Bug and crash fixes

Many thanks to
[@1step2hell](https://github.com/1step2hell),
[@afollestad](https://github.com/afollestad),
[@ansman](https://github.com/ansman),
[@bjdodson](https://github.com/bjdodson),
[@BraisGabin](https://github.com/BraisGabin),
[@EBfVince](https://github.com/EBfVince),
[@jaredsburrows](https://github.com/jaredsburrows),
[@pforhan](https://github.com/pforhan),
[@pyricau](https://github.com/pyricau),
[@tellypresence](https://github.com/tellypresence),
[@wiyarmir](https://github.com/wiyarmir)
for the contributions, bug reports and feature requests.

For more details, see the [2.0-alpha-3 Milestone](https://github.com/square/leakcanary/milestone/8) and the [full diff](https://github.com/square/leakcanary/compare/v2.0-alpha-2...v2.0-alpha-3).

## Version 2.0 Alpha 2 (2019-05-21)

* [#1040](https://github.com/square/leakcanary/pull/1040) Import and analyze hprof files from other devices
* [#1344](https://github.com/square/leakcanary/pull/1344) Computing retained size
* [#1325](https://github.com/square/leakcanary/pull/1325) New notification showing current count of retained instances
* [#1079](https://github.com/square/leakcanary/pull/1079) "Excluded" leaks have been renamed to "Won't fix" leaks to clarify meaning.
* [#1328](https://github.com/square/leakcanary/pull/1328) New leaks are called out in the UI. 
* [#1327](https://github.com/square/leakcanary/pull/1327) LeakSentry can be enabled / disabled and is automatically disabled in non debuggable builds.
* [#1173](https://github.com/square/leakcanary/pull/1173) Experimental: now reporting leaks that only go through weak references (previously reported as "no path to instance")
* [#1339](https://github.com/square/leakcanary/pull/1339) Readded support for Thread name based exclusions
* [#1312](https://github.com/square/leakcanary/pull/1312) Fixed bug causing LeakCanary to stop detecting leaks after the app is killed.
* [#1310](https://github.com/square/leakcanary/pull/1310) [#1313](https://github.com/square/leakcanary/pull/1313) [#1314](https://github.com/square/leakcanary/pull/1314) [#1340](https://github.com/square/leakcanary/pull/1340) [#1337](https://github.com/square/leakcanary/pull/1337) Many API changes
* [#1296](https://github.com/square/leakcanary/pull/1296) [#1293](https://github.com/square/leakcanary/pull/1293) [#1306](https://github.com/square/leakcanary/pull/1306) [#1336](https://github.com/square/leakcanary/pull/1336) Fixed several crashes. 

Many thanks to
[@forrestbice](https://github.com/forrestbice),
[@Foso](https://github.com/Foso),
[@Goddchen](https://github.com/Goddchen),
[@marcosholgado](https://github.com/marcosholgado),
[@orionlee](https://github.com/orionlee),
[@pyricau](https://github.com/pyricau),
[@satoshun](https://github.com/satoshun),
[@ZacSweers](https://github.com/ZacSweers)
for the contributions!

For more details, see the [2.0-alpha-2 Milestone](https://github.com/square/leakcanary/milestone/7) and the [full diff](https://github.com/square/leakcanary/compare/v2.0-alpha-1...v2.0-alpha-2).

## Version 2.0 Alpha 1 (2019-04-23)

![logo](images/logo-2.0-200px.png)

* New [logo](https://github.com/square/leakcanary/wiki/FAQ#who-made-the-logo), thanks [@flickator](https://github.com/flickator)!
* Entirely rewritten to **100% Kotlin**
* Multiple leaks detected in one analysis
    * The heap is dumped when the app goes in the background, or when a minimum of 5 leaks is reached in the foreground.
* Leak grouping
    * Leaks that share similar causes are grouped in the UI.
    * New screens to see the list of groups and each group.
    * Improved leaktrace strings to highlight leak causes.
    * Leaks can be shared to Stack Overflow
* New library: **LeakSentry**.
    * Detects when objects are leaking and triggers LeakCanary
    * Can be used independently in production, for instance to report the number of leaking instances on an OutOfMemoryError crash.
* New heap parser
    * Uses **90% less memory and 6 times** faster than the prior heap parser.
    * Runs in the same process as the app on a low priority thread.
    * No more dependency on Perflib and TroveJ. New dependency on Okio.
    * The old parser is still available as `leakcanary-android-perflib` but will be removed after alpha.
* Labelers can add any string content to leak elements
* 0 code setup, just add the one debug dependency.
* Simpler configuration options
* Updated from support library to Android X

Many thanks to
[@BraisGabin](https://github.com/BraisGabin),
[@colinmarsch](https://github.com/colinmarsch),
[@jrodbx](https://github.com/jrodbx),
[@flickator](https://github.com/flickator),
[@JakeWharton](https://github.com/JakeWharton),
[@pyricau](https://github.com/pyricau),
[@WhatsEmo](https://github.com/WhatsEmo)
for the contributions!

For more details, see the [2.0-alpha-1 Milestone](https://github.com/square/leakcanary/milestone/6) and the [full diff](https://github.com/square/leakcanary/compare/v1.6.3...v2.0-alpha-1).

## Version 1.6.3 (2019-01-10)

* [#1163](https://github.com/square/leakcanary/issues/1163) Fixed leaks being incorrectly classified as "no leak" due to missed GC Roots.
* [#1153](https://github.com/square/leakcanary/issues/1153) `LeakCanary.isInAnalyzerProcess` now correctly returns true in the analyzer process prior to any first leak (could be triggered by starting the leak result activity).
* [#1158](https://github.com/square/leakcanary/issues/1158) Stopped enabling DisplayLeakActivity when not using DisplayLeakService.
* [#1135](https://github.com/square/leakcanary/issues/1135) Fixed IndexOutOfBoundsException for leak traces of size 1.
* [#1163](https://github.com/square/leakcanary/issues/1163) Keep "no leak" heap dumps.

Many thanks to
[@KMaragh](https://github.com/KMaragh),
[@pyricau](https://github.com/pyricau),
[@SebRut](https://github.com/SebRut)
for the code contributions!

For more details, see the [1.6.3 Milestone](https://github.com/square/leakcanary/milestone/5) and the [full diff](https://github.com/square/leakcanary/compare/v1.6.2...v1.6.3).


## Version 1.6.2 (2018-10-16)

* [#1067](https://github.com/square/leakcanary/issues/1067) Fixed TransactionTooLargeException crash (leak analysis would never complete).
* [#1061](https://github.com/square/leakcanary/pull/1061) Detection of Fragment view leaks after Fragment#onDestroyView().
* [#1076](https://github.com/square/leakcanary/pull/1076) Added the FOREGROUND_SERVICE permission for Android P.
* [#1062](https://github.com/square/leakcanary/issues/1062) The LeakCanary toast now always shows correctly. It doesn't show if there is no activity in foreground.
* [#1115](https://github.com/square/leakcanary/issues/1115) Reenabled the DisplayLeakActivity icon on fresh installs.
* [#1100](https://github.com/square/leakcanary/pull/1100) Added nullability annotations to improve Kotlin support.
* Updates to excluded leaks ([commits](https://github.com/square/leakcanary/commits/v1.6.2/leakcanary-android/src/main/java/com/squareup/leakcanary/AndroidExcludedRefs.java)).
* Updates to reachability inspectors ([commits](https://github.com/square/leakcanary/commits/v1.6.2/leakcanary-android/src/main/java/com/squareup/leakcanary/AndroidReachabilityInspectors.java)).

Many thanks to
[@fractalwrench](https://github.com/fractalwrench),
[@hzsweers](https://github.com/hzsweers),
[@Goddchen](https://github.com/Goddchen),
[@igokoro](https://github.com/igokoro),
[@IlyaGulya](https://github.com/IlyaGulya),
[@JakeWharton](https://github.com/JakeWharton),
[@javmarina](https://github.com/javmarina),
[@jokermonn](https://github.com/jokermonn),
[@jrodbx](https://github.com/jrodbx),
[@Parseus](https://github.com/Parseus),
[@pyricau](https://github.com/pyricau),
[@scottkennedy](https://github.com/scottkennedy)
for the code contributions!

### Public API changes

* Subclasses of `AbstractAnalysisResultService` should now override `onHeapAnalyzed(@NonNull AnalyzedHeap analyzedHeap)` instead of `onHeapAnalyzed(@NonNull HeapDump heapDump, @NonNull AnalysisResult result)`

For more details, see the [1.6.2 Milestone](https://github.com/square/leakcanary/milestone/4) and the [full diff](https://github.com/square/leakcanary/compare/v1.6.1...v1.6.2).

## Version 1.6.1 (2018-06-21)

* [#727](https://github.com/square/leakcanary/issues/727) Improved leak analysis: LeakCanary now identifies and highlights the potential causes of the leak.
* [#1011](https://github.com/square/leakcanary/issues/1011) We noticed that computing the retained heap size could take a long time, so it's now optional and off by default.
* [#633](https://github.com/square/leakcanary/pull/633) Support for detecting leaks in instrumentation tests ([see the wiki](https://github.com/square/leakcanary/wiki/Customizing-LeakCanary#running-leakcanary-in-instrumentation-tests)).
* [#985](https://github.com/square/leakcanary/pull/985) Ability to convert leak traces into stack traces for easy remote reporting ([see the wiki](https://github.com/square/leakcanary/wiki/Customizing-LeakCanary#uploading-to-a-server)).
* [#983](https://github.com/square/leakcanary/issues/983) Support for watching destroyed Fragments.
* [#846](https://github.com/square/leakcanary/issues/846) LeakCanary now uses foreground services and displays a notification when the analysis is in progress. This also fixes crashes when analyzing in background on O+.
* The LeakCanary icon (to start to DisplayLeakActivity) is now hidden by default, and only enabled after the first leak is found.
* [#775](https://github.com/square/leakcanary/issues/775) Fixed crash when sharing heap dumps on O+ and added a dependency to the support-core-utils library.
* [#930](https://github.com/square/leakcanary/pull/930) DisplayLeakActivity has a responsive icon.
* [#685](https://github.com/square/leakcanary/issues/685) Stopped doing IO on main thread in DisplayLeakActivity (fixes StrictMode errors).
* [#999](https://github.com/square/leakcanary/pull/999) Updated HAHA to 2.0.4, which uses Trove4j as an external dependency (from jcenter) instead of rebundling it. This is to clarify licences (Apache v2 vs LGPL 2.1).
* Several bug and crash fixes.

Many thanks to [@AdityaAnand1](https://github.com/AdityaAnand1), [@alhah](https://github.com/alhah), [@christxph](https://github.com/christxph), [@csoon03](https://github.com/csoon03), [@daqi](https://github.com/daqi), [@JakeWharton](https://github.com/JakeWharton), [@jankovd](https://github.com/jankovd), [@jrodbx](https://github.com/jrodbx), [@kurtisnelson](https://github.com/kurtisnelson), [@NightlyNexus](https://github.com/NightlyNexus), [@pyricau](https://github.com/pyricau), [@SalvatoreT](https://github.com/SalvatoreT), [@shmuelr](https://github.com/shmuelr), [@tokou](https://github.com/tokou), [@xueqiushi](https://github.com/xueqiushi)
 for the code contributions!

Note: we made a 1.6 release but quickly followed up with 1.6.1 due to [#1058](https://github.com/square/leakcanary/issues/1058).

### Public API changes

* The installed ref watcher singleton is now available via `LeakCanary.installedRefWatcher()`
* `AnalysisResult.leakTraceAsFakeException()` returns an exception that can be used to report and group leak traces to a tool like Bugsnag or Crashlytics.
* New `InstrumentationLeakDetector` and `FailTestOnLeakRunListener` APIs for detecting leaks in instrumentation tests.
* New `Reachability.Inspector` and `RefWatcherBuilder.stethoscopeClasses()` API to establish reachability and help identify leak causes.
* Watching activities can be disabled with `AndroidRefWatcherBuilder.watchActivities(false)`, watching fragments can be disabled with `AndroidRefWatcherBuilder.watchFragments(false)`
* `LeakCanary.setDisplayLeakActivityDirectoryProvider()` is deprecated and replaced with `LeakCanary.setLeakDirectoryProvider()`
* New `RefWatcherBuilder.computeRetainedHeapSize()` API to enable the computing of the retained heap size (off by default).

For more details, see the [1.6.1 Milestone](https://github.com/square/leakcanary/milestone/3) and the [full diff](https://github.com/square/leakcanary/compare/v1.5.4...v1.6.1).

## Version 1.5.4 *(2017-09-22)*

* Restore Java 7 compatibility in leakcanary-watcher

## Version 1.5.3 *(2017-09-17)*

* Fix broken 1.5.2 [build](https://github.com/square/leakcanary/issues/815)
* Convert leakcanary-watcher from Android library to Java library
* Disable finish animations in RequestStoragePermissionActivity
* Corrected README sample for Robolectric tests

For more details, see the [full diff](https://github.com/square/leakcanary/compare/v1.5.2...v1.5.3).

## Version 1.5.2 *(2017-08-09)*

* New excluded leaks
* Move Leakcanary UI into leak analyzer process
* Ignore computing retained sizes for bitmaps on O+
* Add notification channel for persistent messages on O+
* Exclude permission activity from recents menu
* Updated README and sample for handling Robolectric tests

For more details, see the [full diff](https://github.com/square/leakcanary/compare/v1.5.1...v1.5.2).

## Version 1.5.1 *(2017-04-25)*

* New excluded leaks
* Fix java.util.MissingFormatArgumentException in DisplayLeakService
* Separate task affinities for different apps
* Bump minSdk to 14
* Fix HahaHelper for O Preview
  
For more details, see the [full diff](https://github.com/square/leakcanary/compare/v1.5...v1.5.1).

## Version 1.5 *(2016-09-28)*

* New excluded leaks
* Added `LeakCanary.isInAnalyzerProcess()` to the no-op jar
* Fixed several file access issues:
    * No more cleanup on startup, we rotate the heap dump files on every new heap dump.
    * LeakCanary now falls back to the app directory until it can write to the external storage.
* Leak notifications now each use a distinct notification instead of erasing each other.
* If LeakCanary can't perform a heap dump for any reason (e.g. analysis in progress, debugger attached), it retries later with an exponential backoff.
* Added confirmation dialog when user deletes all leaks.
* Replace the two LeakCanary configuration methods with a builder that provides more flexibility, see `LeakCanary.refWatcher()`.

For more details, see the [full diff](https://github.com/square/leakcanary/compare/v1.4...v1.5).

### Public API changes

* New `HeapAnalyzer.findTrackedReferences()` method for headless analysis when you have no context on what leaked.
* Added `LeakCanary.isInAnalyzerProcess()` to the no-op jar
* Added `LeakCanary.refWatcher()` which returns an `AndroidRefWatcherBuilder` that extends `RefWatcherBuilder` and lets you fully customize the `RefWatcher` instance.
* Removed `LeakCanary.install(Application, Class)` and `LeakCanary.androidWatcher(Context, HeapDump.Listener, ExcludedRefs)`.
* Removed `R.integer.leak_canary_max_stored_leaks` and `R.integer.leak_canary_watch_delay_millis`, those can now be set via `LeakCanary.refWatcher()`.
* Updated the `LeakDirectoryProvider` API to centralize all file related responsibilities.
* `RefWatcher` is now constructed with a `WatchExecutor` which executes a `Retryable`, instead of an `Executor` that executes a `Runnable`.
* `HeapDumper.NO_DUMP` was renamed `HeapDumper.RETRY_LATER`

## Version 1.4 *(2016-09-11)*

* Fix false negative where GC root is of type android.os.Binder [#482](https://github.com/square/leakcanary/issues/482)
* Update HAHA to 2.0.3; clear compiler warnings [#563](https://github.com/square/leakcanary/issues/563) 
* Correct some mistakes in German translation [#516](https://github.com/square/leakcanary/pull/516)
* Don't loop when storage permission denied [#422](https://github.com/square/leakcanary/issues/422)
* Remove old references to "__" prefixed resources [#477](https://github.com/square/leakcanary/pull/477)
* Fix permission crash for DisplayLeakActivity on M [#382](https://github.com/square/leakcanary/issues/382)
* Fix NPE when thread name not found in heap dump [#417](https://github.com/square/leakcanary/issues/417)
* Add version info to stacktrace [#473](https://github.com/square/leakcanary/issues/473)

## Version 1.4-beta2 *(2016-03-23)*

* Add reason for ignoring to analysis result [#365](https://github.com/square/leakcanary/issues/365).
* Lower memory usage when parsing heap dumps on M [#223](https://github.com/square/leakcanary/issues/223).
* Fix NPE in LeakCanaryInternals.isInServiceProcess() [#449](https://github.com/square/leakcanary/issues/449).
* New ignored Android SDK leaks [#297](https://github.com/square/leakcanary/issues/297),[#322](https://github.com/square/leakcanary/issues/322).
* Use leakcanary-android-no-op in test builds [#143](https://github.com/square/leakcanary/issues/143).
* Fixes to allow LeakCanary to work with ProGuard [#398](https://github.com/square/leakcanary/pull/398).
* Optimize png assets [#406](https://github.com/square/leakcanary/pull/406).
* Fix delete button not working on error views [#408](https://github.com/square/leakcanary/pull/408).
* Add German translation [#437](https://github.com/square/leakcanary/pull/437).

## Version 1.4-beta1 *(2016-01-08)*

* Switched to [HAHA 2.0.2](https://github.com/square/haha/blob/master/CHANGELOG.md#version-202-2015-07-20) with uses Perflib instead of MAT under the hood [#219](https://github.com/square/leakcanary/pull/219). This fixes crashes and improves speed a lot.
* We can now parse Android M heap dumps [#267](https://github.com/square/leakcanary/issues/267), although there are still memory issues (see [#223](https://github.com/square/leakcanary/issues/223)).
* Excluded leaks are now reported as well and available in the display leak activity.
* Added ProGuard configuration for [#132](https://github.com/square/leakcanary/issues/132).
* Many new ignored Android SDK leaks.
* Added excluded leaks to text report [#119](https://github.com/square/leakcanary/issues/119).
* Added LeakCanary SHA to text report [#120](https://github.com/square/leakcanary/issues/120).
* Added CanaryLog API to replace the logger: [#201](https://github.com/square/leakcanary/issues/201).
* Renamed all resources to begin with `leak_canary_` instead of `__leak_canary`[#161](https://github.com/square/leakcanary/pull/161)
* No crash when heap dump fails [#226](https://github.com/square/leakcanary/issues/226).
* Add retained size to leak reports [#162](https://github.com/square/leakcanary/issues/162).

### Public API changes

* AnalysisResult.failure is now a `Throwable` instead of an `Exception`. Main goal is to catch and correctly report OOMs while parsing.
* Added ARRAY_ENTRY to LeakTraceElement.Type for references through array entries.
* Renamed `ExcludedRefs` fields.
* Each `ExcludedRef` entry can now be ignored entirely or "kept only if no other path".
* Added support for ignoring all fields (static and non static) for a given class.

## Version 1.3.1 *(2015-05-16)*

* Heap dumps and analysis results are now saved on the sd card: [#21](https://github.com/square/leakcanary/issues/21).
* `ExcludedRef` and `AndroidExcludedRefs` are customizable: [#12](https://github.com/square/leakcanary/issues/12) [#73](https://github.com/square/leakcanary/issues/73).
* 7 new ignored Android SDK leaks: [#1](https://github.com/square/leakcanary/issues/1) [#4](https://github.com/square/leakcanary/issues/4) [#32](https://github.com/square/leakcanary/issues/32) [#89](https://github.com/square/leakcanary/pull/89) [#82](https://github.com/square/leakcanary/pull/82) [#97](https://github.com/square/leakcanary/pull/97).
* Fixed 3 crashes in LeakCanary: [#37](https://github.com/square/leakcanary/issues/37) [#46](https://github.com/square/leakcanary/issues/46) [#66](https://github.com/square/leakcanary/issues/66).
* Fixed StrictMode thread policy violations: [#15](https://github.com/square/leakcanary/issues/15).
* Updated `minSdkVersion` from `9` to `8`: [#57](https://github.com/square/leakcanary/issues/57).
* Added LeakCanary version name to `LeakCanary.leakInfo()`: [#49](https://github.com/square/leakcanary/issues/49).
* `leakcanary-android-no-op` is lighter, it does not depend on `leakcanary-watcher` anymore, only 2 classes now: [#74](https://github.com/square/leakcanary/issues/74).
* Adding field state details to the text leak trace.
* A Toast is displayed while the heap dump is in progress to warn that the UI will freeze: [#20](https://github.com/square/leakcanary/issues/49). You can customize the toast by providing your own layout named `__leak_canary_heap_dump_toast.xml` (e.g. you could make it an empty layout).
* If the analysis fails, the result and heap dump are kept so that it can be reported to LeakCanary: [#102](https://github.com/square/leakcanary/issues/102).
* Update to HAHA 1.3 to fix a 2 crashes [#3](https://github.com/square/leakcanary/issues/3) [46](https://github.com/square/leakcanary/issues/46)

### Public API changes

* When upgrading from 1.3 to 1.3.1, previously saved heap dumps will not be readable any more, but they won't be removed from the app directory. You should probably uninstall your app.
* Added `android.permission.WRITE_EXTERNAL_STORAGE` to `leakcanary-android` artifact.
* `LeakCanary.androidWatcher()` parameter types have changed (+ExcludedRefs).
* `LeakCanary.leakInfo()` parameter types have changed (+boolean)
* `ExcludedRef` is now serializable and immutable, instances can be created using `ExcludedRef.Builder`.
* `ExcludedRef` is available in `HeapDump`
* `AndroidExcludedRefs` is an enum, you can now pick the leaks you want to ignore in `AndroidExcludedRefs` by creating an `EnumSet` and calling `AndroidExcludedRefs.createBuilder()`.
* `AndroidExcludedRefs.createAppDefaults()` & `AndroidExcludedRefs.createAndroidDefaults()` return a `ExcludedRef.Builder`.
* `ExcludedRef` moved from `leakcanary-analyzer` to `leakcanary-watcher`

## Version 1.3 *(2015-05-08)*

Initial release.

### Dependencies
