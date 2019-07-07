# FAQ

## Can a leak be caused by the Android SDK?

Yes. There are a number of known memory leaks that have been fixed over time in AOSP as well as in manufacturer implementations. When such a leak occurs, there is little you can do as an app developer to fix it. For that reason, LeakCanary has a built-in list of known Android leaks to ignore: [AndroidExcludedRefs.kt](https://github.com/square/leakcanary/blob/master/leakcanary-analyzer/src/main/java/leakcanary/AndroidExcludedRefs.kt).

If you find a new one, please [create an issue](https://github.com/square/leakcanary/issues/new/choose) and follow these steps:

1. Provide the entire leak trace information (reference key, device, etc), and use backticks (`) for formatting.
2. Read the AOSP source for that version of Android, and try to figure out why it happens. You can easily navigate through SDK versions [android/platform_frameworks_base](https://github.com/android/platform_frameworks_base).
3. Check if it happens on the latest version of Android, and otherwise use blame to find when it was fixed.
4. If it's still happening, build a simple repro case.
5. File an issue on [b.android.com](http://b.android.com) with the leak trace and the repro case.
6. Create a PR in LeakCanary to update `AndroidExcludedRefs.kt`. Optional: if you find a hack to clear that leak on previous versions of Android, feel free to document it.

## How do I share a leak trace?

* Go to the leak screen, click the overflow menu and select *Share Info*.
* You can also find the leak trace in Logcat.

## How can I dig beyond the leak trace?

Sometimes the leak trace isn't enough and you need to dig into a heap dump with [MAT](http://eclipse.org/mat/) or [YourKit](https://www.yourkit.com/).

* Go to a heap analysis screen, click the overflow menu and select *Share Heap Dump*.

Here's how you can find the leaking instance in the heap dump:

1. Look for all instances of `leakcanary.KeyedWeakReference`.
2. For each of these, look at the `key` field.
3. Find the `KeyedWeakReference` that has a `key` field equal to the reference key reported by LeakCanary.
4. The `referent` field of that `KeyedWeakReference` is your leaking object.
5. From then on, the matter is in your hands. A good start is to look at the shortest path to GC Roots (excluding weak references).

## How many methods does LeakCanary add?

**0**. LeakCanary is a debug only library.

## How do I use the SNAPSHOT version?

Update your dependencies to the latest SNAPSHOT (see [build.gradle](https://github.com/square/leakcanary/blob/master/build.gradle)):

```gradle
 dependencies {
   debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-4-SNAPSHOT'
 }
```

Add Sonatype's `snapshots` repository:

```
  repositories {
    mavenCentral()
    maven {
      url 'https://oss.sonatype.org/content/repositories/snapshots/'
    }
  }
```

Status of the snapshot build: [![Build Status](https://travis-ci.org/square/leakcanary.svg?branch=master)](https://travis-ci.org/square/leakcanary)

## Who's behind LeakCanary?

LeakCanary was created and open sourced by [@pyricau](https://github.com/pyricau), with [many contributions](https://github.com/square/leakcanary/graphs/contributors) from the community.

## Why is it called LeakCanary?

The name **LeakCanary** is a reference to the expression [canary in a coal mine](http://en.wiktionary.org/wiki/canary_in_a_coal_mine), because LeakCanary is a sentinel used to detect risks by providing advance warning of a danger. Props to [@edenman](https://github.com/edenman) for suggesting it!

## Who made the logo?

* [@pyricau](https://github.com/pyricau) quickly made the [first version](https://github.com/square/leakcanary/blob/f0cc04dfbf3cca92a669f0d250034d410eb05816/assets/icon_512.png) of the logo. It was based on cliparts from [Android Asset Studio](http://romannurik.github.io/AndroidAssetStudio/icons-generic.html), mixed with the selection from a photo of a Canary. The exclamation mark means danger, the shield stands for protection, and the bird, well, is a canary.
* [@romainguy](https://github.com/romainguy) turned the ugly logo into [a nice vector asset](https://github.com/square/leakcanary/pull/36).
* [@flickator](https://github.com/flickator) designed [a much nicer logo](https://github.com/square/leakcanary/pull/1269) for LeakCanary 2.0!

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/logo-2.0.png" />
</p>
