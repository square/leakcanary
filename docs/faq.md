# FAQ

## Can a leak be caused by the Android SDK?

Yes. There are a number of known memory leaks that have been fixed over time in AOSP as well as in manufacturer implementations. When such a leak occurs, there is little you can do as an app developer to fix it. For that reason, LeakCanary has a built-in list of known Android leaks to recognize, called Library Leaks (see [Categorizing leaks](fundamentals-how-leakcanary-works.md#4-categorizing-leaks)).

If you find a new one, please [create an issue](https://github.com/square/leakcanary/issues/new/choose) (choose **🤖Leak in Android SDK / support library**) and follow these steps:

1. Provide the entire leak trace information (including metadata), and use backticks (`) for formatting.
2. Read the AOSP source for that version of Android, and try to figure out why it happens. You can easily navigate through SDK versions by switching branches on the GitHub mirror: [android/platform_frameworks_base](https://github.com/android/platform_frameworks_base).
3. Check if it happens on the latest version of Android, and otherwise use blame to find when it was fixed.
4. If it's still happening, build a simple repro case.
5. File an issue on [b.android.com](http://b.android.com) with the leak trace and the repro case. Please remember to follow up the issue when there are new responses. [b/176886060](https://issuetracker.google.com/issues/176886060) is a good example of effective and respectful communication.
6. Create a PR in LeakCanary to update [AndroidReferenceMatchers](/leakcanary/api/shark-android/shark-android/shark/-android-reference-matchers/). Optional: if you find a hack to clear that leak on previous versions of Android, feel free to document it.

## How do I know if LeakCanary is running?

You can confirm that LeakCanary starts correctly by filtering on the LeakCanary tag in Logcat:

```
$ adb logcat | grep LeakCanary

D/LeakCanary: Installing AppWatcher
```

If you do not see `Installing AppWatcher` in the logs, check your dependencies (`./gradlew app:dependencies`) and make sure LeakCanary is there.

Note that LeakCanary is automatically disabled in tests (see [LeakCanary test environment detection](recipes.md#leakcanary-test-environment-detection)):

```
$ adb logcat | grep LeakCanary

D/LeakCanary: Installing AppWatcher
D/LeakCanary: JUnit detected in classpath, app is running tests => disabling heap dumping & analysis
D/LeakCanary: Updated LeakCanary.config: Config(dumpHeap=false)
```

## Where does LeakCanary store heap dumps?

In a `leakcanary` folder inside the app's [no backup directory](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()),
i.e. `/data/data/com.example/no_backup/leakcanary/` where `com.example` is your app package name.
That directory needs no permission on any Android version, and heap dumps stored there are excluded
from Android Auto Backup, so they never count against the user's backup quota.

LeakCanary keeps the [LeakCanary.Config.maxStoredHeapDumps](/leakcanary/api/leakcanary-android-core/leakcanary-android-core/leakcanary/-leak-canary/-config/max-stored-heap-dumps/)
most recent heap dumps, 7 by default, and deletes the older ones.

To pull a heap dump off the device, either share it from the LeakCanary UI (go to a heap analysis
screen, click the overflow menu and select *Share Heap Dump*), or read it through the app's own
user with `run-as`, which works because LeakCanary is a `debugImplementation` dependency and the app
is therefore debuggable:

```
adb exec-out run-as com.example cat no_backup/leakcanary/2026-07-31_22-30-41_484.hprof > dump.hprof
```

## How can I dig beyond the leak trace?

Sometimes the leak trace isn't enough and you need to dig into a heap dump with [MAT](http://eclipse.org/mat/) or [YourKit](https://www.yourkit.com/).

* Go to a heap analysis screen, click the overflow menu and select *Share Heap Dump*.

Here's how you can find the leaking instance in the heap dump:

1. Look for all instances of `leakcanary.KeyedWeakReference`.
2. For each of these, look at the `key` field.
3. Find the `KeyedWeakReference` that has a `key` field equal to the reference key reported by LeakCanary.
4. The `referent` field of that `KeyedWeakReference` is your leaking object.
5. From then on, the matter is in your hands. A good start is to look at the shortest path to GC Roots (excluding weak references).

## Why does LeakCanary report unreachable objects?

A heap analysis can end with a section like this:

```
====================================
1 UNREACHABLE OBJECTS

An unreachable object is still in memory but LeakCanary could not find a strong reference path
from GC roots.
```

LeakCanary watched an object, the object was still there when the heap was dumped, and yet nothing strongly reachable points at it. That can be a race, with the object becoming collectable between the retained check and the heap dump. It can also be the opposite of a race: the object really is only weakly reachable, and it is still not going away even though the heap dump shows there are no strong reference paths to that object.

`WeakReference.get()` hands back an ordinary strong reference, and a collector that moves objects has to guarantee the caller never sees a stale pointer. ART's Concurrent Copying collector, the default since Android 8, does that with a read barrier, and it deliberately arranges for `get()` to trip it: it [leaves a `Reference` object gray while its referent is unmarked](https://cs.android.com/android/platform/superproject/main/+/main:art/runtime/gc/collector/concurrent_copying.cc) so that, as the comment there puts it, "if `GetReferent()` is called, it triggers the read-barrier to process the referent before use". The barrier marks the referent, and reference processing later clears only the references whose referent is still unmarked. So a weak reference that is read during marking is not cleared that cycle, and its target survives it. Read a weak reference once and you lose one collection cycle. Read it every frame and you can lose all of them.

The clearest example of that is a framework bug with a thirteen year history. `ObjectAnimator` animates a target object, and it used to hold that target through a `WeakReference` so that an animator left running couldn't keep a view hierarchy alive. That weak reference was [added in January 2011](https://android.googlesource.com/platform/frameworks/base/+/ec84c3a189e4aa70aa6ea8ba712e5a4f260a153b) to let old view hierarchies be collected faster during rotation, [taken back out three days later](https://android.googlesource.com/platform/frameworks/base/+/51ae5fc2d22a7bb616f432d7bac66bbbf8a1927f) because it broke animations, and [put back in June 2014](https://android.googlesource.com/platform/frameworks/base/+/87ac5f60e20fba335497aa9dc03b7c29c4b966a2), where it stayed for the next decade.

The trouble is what an animator does with it. Animating means reading the target on every frame, around 60 times a second, and on a collector with a read barrier every one of those reads marks the target. An infinite `ObjectAnimator` that nobody cancelled therefore pins its target for as long as the process lives, through a reference that was put there specifically to stop that from happening.

When the target is a view, the view outlives the activity it belonged to. LeakCanary sees the retention, dumps the heap, goes looking for whatever holds the view — and finds nothing, because nothing does, strongly. The report is an unreachable object: memory demonstrably being wasted, with no path to explain it and nothing to go and fix.

[LeakCanary 2.8](changelog.md#objectanimator-leaks) made it explainable. A reference reader named `ANIMATOR_WEAK_REF_SUCKS` recognizes an `ObjectAnimator` whose `mTarget` is a `WeakReference` and republishes the referent as a **virtual reference**: a reference LeakCanary puts on the graph that no field actually holds. It is marked low priority, so the path finder routes through it only when there is no other explanation, which is exactly this situation. The trace then names `ObjectAnimator.mTarget` as the suspect. Virtual references weren't built for this — they came out of [showing a collection the way you would write it](design.md#references-are-shown-the-way-you-would-write-them), which needs the same ability to put a reference on the graph that no single field holds.

That still left the bug itself in the framework. It was [filed against AOSP](https://issuetracker.google.com/issues/212993949) and fixed in Android 15 two years later by [a commit that deletes the `WeakReference` outright](https://android.googlesource.com/platform/frameworks/base/+/392832f9580ff38f1fb0d7de47dbcb17eaaededf), carrying the same diagnosis in two lines: "ObjectAnimator was using a WeakReference for the target, but because it is animated every frame, the WeakReference can never be collected."

The pattern is still in the framework though. [`Drawable.mCallback`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/graphics/java/android/graphics/drawable/Drawable.java;l=194) is a `WeakReference` too, [made one in December 2010](https://android.googlesource.com/platform/frameworks/base/+/f2a47782f31b58d2d31bd00b50fe43604af8b9c2) with the commit message "Many memory leaks occur because of long lived drawables. This should help." A drawable driven by an infinite `ObjectAnimator` reads that callback every frame and retains the view it points at, by exactly the mechanism above. There is no AOSP ticket for that one; it is tracked on the LeakCanary side in [#2116](https://github.com/square/leakcanary/issues/2116).

How bad this is depends on the collector, not on the Android version. ART's newer `userfaultfd`-based Concurrent Mark-Compact collector [eliminates the read barrier](https://android-developers.googleblog.com/2022/08/android-13-is-in-aosp.html), so reading a weak reference no longer marks its target. Retention then depends on the value happening to be live in a thread's stack or registers at one of the collector's root scans, rather than being guaranteed by the read. For the animation case that should turn near-certain retention into occasional retention — the object survives some cycles and gets collected in one where the read didn't line up with a root scan — which follows from how the collector works rather than from a published measurement. It doesn't make the bug harmless, it makes it less reproducible. And you can't tell which collector you have from the Android release: Concurrent Mark-Compact ships in the updatable ART module and is gated on kernel support, so two devices on the same version of Android can be running different collectors.

## How does LeakCanary get installed by only adding a dependency?

On Android, content providers are created after the Application instance is created but before Application.onCreate() is called. The `leakcanary-object-watcher-android` artifact has a non exported ContentProvider defined in its `AndroidManifest.xml` file. When that ContentProvider is installed, it adds activity and fragment lifecycle listeners to the application.

## How many methods does LeakCanary add?

**0**. LeakCanary is a debug only library.

## How do I use the SNAPSHOT version?

Update your dependencies to the latest SNAPSHOT (see [build.gradle](https://github.com/square/leakcanary/blob/main/build.gradle)):

```gradle
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.next_release }}-SNAPSHOT'
}
```

Add Sonatype's `snapshots` repository:

```gradle
repositories {
  mavenCentral()
  maven {
    url 'https://s01.oss.sonatype.org/content/repositories/snapshots/'
  }
}
```

Status of the snapshot build: [![Build Status](https://travis-ci.org/square/leakcanary.svg?branch=main)](https://travis-ci.org/square/leakcanary)

## Who's behind LeakCanary?

LeakCanary was created and open sourced by [@pyricau](https://github.com/pyricau), with [many contributions](https://github.com/square/leakcanary/graphs/contributors) from the community.

## Why is it called LeakCanary?

The name **LeakCanary** is a reference to the expression [canary in a coal mine](http://en.wiktionary.org/wiki/canary_in_a_coal_mine), because LeakCanary is a sentinel used to detect risks by providing advance warning of a danger. Props to [@edenman](https://github.com/edenman) for suggesting it!

## Who made the logo?

* [@pyricau](https://github.com/pyricau) quickly made the [first version](https://github.com/square/leakcanary/blob/f0cc04dfbf3cca92a669f0d250034d410eb05816/assets/icon_512.png) of the logo. It was based on cliparts from [Android Asset Studio](http://romannurik.github.io/AndroidAssetStudio/icons-generic.html), mixed with the selection from a photo of a Canary. The exclamation mark means danger, the shield stands for protection, and the bird, well, is a canary.
* [@romainguy](https://github.com/romainguy) turned the ugly logo into [a nice vector asset](https://github.com/square/leakcanary/pull/36).
* [@flickator](https://github.com/flickator) designed [a much nicer logo](https://github.com/square/leakcanary/pull/1269) for LeakCanary 2.0!

<p align="center">
<img src="../images/logo-2.0.png" />
</p>
