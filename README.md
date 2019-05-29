# 🐤 LeakCanary

A memory leak detection library for Android.

*“A small leak will sink a great ship.”* - Benjamin Franklin

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/screenshot-2.0.png"/>
</p>

## Getting started

Add LeakCanary to `build.gradle`:

```gradle
dependencies {
  // debugImplementation because LeakCanary should only run in debug builds.
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-2'
}
```

**That's it, there is no code change needed!** LeakCanary will automatically show a notification when a memory leak is detected in debug builds.

What's next?
* Learn the [Fundamentals](#fundamentals)
* Try the [code recipes](#code-recipes)
* Read the [FAQ](#faq)
* Watch [recorded presentations](https://github.com/square/leakcanary/wiki/Recorded-Presentations)
* Read [blog articles](https://github.com/square/leakcanary/wiki/Blog-Articles)

Note: **LeakCanary 2 is in alpha**.
* Check out the [migration guide](https://github.com/square/leakcanary/wiki/Migrating-to-LeakCanary-2.0).
* Here is the [change log](https://github.com/square/leakcanary/blob/master/CHANGELOG.md#version-20-alpha-2-2019-05-21).
* To set up LeakCanary 1.6, go to the [1.6 Readme](https://github.com/square/leakcanary/blob/master/README-1.6.md).

## Fundamentals

### What is a memory leak?

In a Java based runtime, a memory leak is a programming error that causes an application to keep a reference to an object that is no longer needed. As a result, the memory allocated for that object cannot be reclaimed, eventually leading to an OutOfMemoryError crash.

For example, an Android activity instance is no longer needed after its `onDestroy()` method is called, and storing a reference to that activity in a static field would prevent it from being garbage collected.

### Why should I use LeakCanary?

Memory leaks are very common in Android apps. OutOfMemoryError (OOM) is the top crash for most apps on the play store, however that's usually not counted correctly. When memory is low the OOM can be thrown from anywhere in your code, which means every OOM has a different stacktrace and they're counted as different crashes.

When we first enabled LeakCanary in the Square Point Of Sale app, we were able to find and fix several leaks and reduced the OutOfMemoryError crash rate by **94%**.

### How does LeakCanary work?

* The library automatically watches destroyed activities and destroyed fragments using weak references. You can also watch any instance that is no longer needed, e.g. a detached view.
* If the weak references aren't cleared, after waiting 5 seconds and running the GC, the watched instances are considered *retained*, and potentially leaking.
* When the number of retained instances reaches a threshold, LeakCanary dumps the Java heap into a `.hprof` file stored on the file system. The default threshold is 5 retained instances when the app is visible, 1 otherwise.
* LeakCanary parses the `.hprof` file and finds the chain of references that prevents retained instances from being garbage collected (**leak trace**). A leak trace is technically the *shortest strong reference path from GC Roots to retained instances*, but that's a mouthful.
* Once the leak trace is determined, LeakCanary uses its built in knowledge of the Android framework to deduct which instances in the leak trace are leaking. You can help LeakCanary by providing **Reachability inspectors** tailored to your own app.
* Using the reachability information, LeakCanary narrows down the reference chain to a sub chain of possible leak causes, and displays the result. Leaks are grouped by identical sub chain.

### How do I fix a memory leak?
To fix a memory leak, you need to look at the sub chain of possible leak causes and find which reference is causing the leak, i.e. which reference should have been cleared at the time of the leak. LeakCanary highlights with a red underline wave the references that are the possible causes of the leak.

If you cannot figure out a leak, **please do not file an issue**. Instead, create a [Stack Overflow question](http://stackoverflow.com/questions/tagged/leakcanary) using the *leakcanary* tag.

### LeakCanary artifacts

LeakCanary is released as several distinct libraries:

* LeakSentry
  * Detects retained instances.
  * Suitable for release builds.
  * Artifact id: `com.squareup.leakcanary:leaksentry`.
* LeakCanary
  * Dumps the heap and analyzes it.
  * Currently only suitable for debug builds.
  * Depends on LeakSentry.
  * Artifact id: `com.squareup.leakcanary:leakcanary-android`.
* LeakCanary for Instrumentation tests
  * Fails tests if a leak is detected
  * Only suitable for Instrumentation tests
  * Configures LeakCanary to wait for the end of tests before dumping the heap.
  * Artifact id: `com.squareup.leakcanary:leakcanary-android-instrumentation`.
  * See [Running LeakCanary in instrumentation tests](#running-leakcanary-in-instrumentation-tests)

## Code Recipes

If you think a recipe might be missing or you're not sure that what you're trying to achieve is possible with the current APIs, please [file an issue](https://github.com/square/leakcanary/issues/new/choose). Your feedback help us make LeakCanary better for the entire community.

### Configuring LeakSentry

LeakSentry can be configured by replacing `LeakSentry.config`:
```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakSentry.config = LeakSentry.config.copy(watchFragmentViews = false)
  }
}
```

### Configuring LeakCanary

LeakCanary can be configured by replacing `LeakCanary.config`:

```kotlin
disableLeakCanaryButton.setOnClickListener {
  LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
}
```

### Watching objects with a lifecycle

In your application, you may have other objects with a lifecycle, such as fragments, services, Dagger components, etc. Use `LeakSentry.refWatcher` to watch instances that should be garbage collected:

```kotlin
class MyService : Service {

  // ...

  override fun onDestroy() {
    super.onDestroy()
    LeakSentry.refWatcher.watch(this)
  }
}
```

### Counting retained instances in production

`com.squareup.leakcanary:leakcanary-android` should only be used in debug builds. It depends on `com.squareup.leakcanary:leaksentry` which you can use in production to track and count retained instances.

In your `build.gradle`:

```gradle
dependencies {
  implementation 'com.squareup.leakcanary:leaksentry:2.0-alpha-2'
}
```

In your leak reporting code:
```kotlin
val retainedInstanceCount = LeakSentry.refWatcher.retainedKeys.size
```

### Running LeakCanary in instrumentation tests

Add the `leakcanary-android-instrumentation` dependency to your instrumentation tests:

```
androidTestImplementation "com.squareup.leakcanary:leakcanary-android-instrumentation:${leakCanaryVersion}"
```

Add the dedicated run listener to `defaultConfig` in your `build.gradle`:

```
android {
  defaultConfig {
    // ...

    testInstrumentationRunner "android.support.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArgument "listener", "leakcanary.FailTestOnLeakRunListener"
  }
}
```

Run the instrumentation tests:

```
./gradlew leakcanary-sample:connectedCheck
```

You can extend `FailTestOnLeakRunListener` to customize the behavior.

### Icon and label

The activity that displays leaks comes with a default icon and label, which you can change by providing `R.mipmap.leak_canary_icon` and `R.string.leak_canary_display_activity_label` in your app:

```
res/
  mipmap-hdpi/
    leak_canary_icon.png
  mipmap-mdpi/
    leak_canary_icon.png
  mipmap-xhdpi/
    leak_canary_icon.png
  mipmap-xxhdpi/
    leak_canary_icon.png
  mipmap-xxxhdpi/
    leak_canary_icon.png
   mipmap-anydpi-v26/
     leak_canary_icon.xml
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <string name="leak_canary_display_activity_label">MyLeaks</string>
</resources>
```

### Uploading to a server

You can change the default behavior to upload the analysis result to a server of your choosing.

Create a custom `AnalysisResultListener` that delegates to the default: 

```kotlin
class LeakUploader : AnalysisResultListener {
  override fun invoke(
    application: Application,
    heapAnalysis: HeapAnalysis
  ) {
    TODO("Upload heap analysis to server")

    // Delegate to default behavior (notification and saving result)
    DefaultAnalysisResultListener(application, heapAnalysis)
  }
}
```

Set `analysisResultListener` on the LeakCanary config:

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(analysisResultListener = LeakUploader())
  }
}
```


### Identifying 3rd party leaks as "won't fix"

Set `exclusionsFactory` on the LeakCanary config to a `ExclusionsFactory` that delegates to the default one and then and add custom exclusions:

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(exclusionsFactory = { hprofParser ->
      val defaultFactory = AndroidExcludedRefs.exclusionsFactory(AndroidExcludedRefs.appDefaults)
      val appDefaults = defaultFactory(hprofParser)
      val customExclusion = Exclusion(
          type = StaticFieldExclusion("com.thirdparty.SomeSingleton", "sContext"),
          status = Exclusion.Status.WONT_FIX_LEAK,
          reason = "SomeSingleton in library X has a static field leaking a context."
      )
      appDefaults + customExclusion
    })
  }
}
```

### Identifying leaking instances and labeling instances

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    val customLabeler: Labeler = { parser, node ->
      listOf("Heap dump object id is ${node.instance}")
    }
    val labelers = AndroidLabelers.defaultAndroidLabelers(this) + customLabeler

    val customInspector: LeakInspector = { parser, node ->
      with(parser) {
        if (node.instance.objectRecord.isInstanceOf("com.example.MySingleton")) {
          LeakNodeStatus.notLeaking("MySingleton is a singleton")
        } else LeakNodeStatus.unknown()
      }
    }
    val leakInspectors = AndroidLeakInspectors.defaultAndroidInspectors() + customInspector

    LeakCanary.config = LeakCanary.config.copy(labelers = labelers, leakInspectors = leakInspectors)
  }
}
```

## FAQ

### Can a leak be caused by the Android SDK?

Yes. There are a number of known memory leaks that have been fixed over time in AOSP as well as in manufacturer implementations. When such a leak occurs, there is little you can do as an app developer to fix it. For that reason, LeakCanary has a built-in list of known Android leaks to ignore: [AndroidExcludedRefs.kt](https://github.com/square/leakcanary/blob/master/leakcanary-android-core/src/main/java/leakcanary/AndroidExcludedRefs.kt).

If you find a new one, please [create an issue](https://github.com/square/leakcanary/issues/new/choose) and follow these steps:

1. Provide the entire leak trace information (reference key, device, etc), and use backticks (`) for formatting.
2. Read the AOSP source for that version of Android, and try to figure out why it happens. You can easily navigate through SDK versions [android/platform_frameworks_base](https://github.com/android/platform_frameworks_base).
3. Check if it happens on the latest version of Android, and otherwise use blame to find when it was fixed.
4. If it's still happening, build a simple repro case
5. File an issue on [b.android.com](http://b.android.com) with the leak trace and the repro case
6. Create a PR in LeakCanary to update `AndroidExcludedRefs.kt`. Optional: if you find a hack to clear that leak on previous versions of Android, feel free to document it.

### How do I share a leak trace?

* Go to the leak screen, click the overflow menu and select *Share Info*.
* You can also find the leak trace in Logcat.

### How can I dig beyond the leak trace?

Sometimes the leak trace isn't enough and you need to dig into a heap dump with [MAT](http://eclipse.org/mat/) or [YourKit](https://www.yourkit.com/).

* Go to a heap analysis screen, click the overflow menu and select *Share Heap Dump*.

Here's how you can find the leaking instance in the heap dump:

1. Look for all instances of `leakcanary.KeyedWeakReference`
2. For each of these, look at the `key` field.
3. Find the `KeyedWeakReference` that has a `key` field equal to the reference key reported by LeakCanary.
4. The `referent` field of that `KeyedWeakReference` is your leaking object.
5. From then on, the matter is in your hands. A good start is to look at the shortest path to GC Roots (excluding weak references).

### How many methods does LeakCanary add?

**0**. LeakCanary is a debug only library.

### How do I use the SNAPSHOT version?

Update your dependencies to the latest SNAPSHOT (see [build.gradle](https://github.com/square/leakcanary/blob/master/build.gradle)):

```gradle
 dependencies {
   debugCompile 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-3-SNAPSHOT'
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

### Who's behind LeakCanary?

LeakCanary was created and open sourced by [@pyricau](https://github.com/pyricau), with [many contributions](https://github.com/square/leakcanary/graphs/contributors) from the community.

### Why is it called LeakCanary?

The name **LeakCanary** is a reference to the expression [canary in a coal mine](http://en.wiktionary.org/wiki/canary_in_a_coal_mine), because LeakCanary is a sentinel used to detect risks by providing advance warning of a danger. Props to [@edenman](https://github.com/edenman) for suggesting it!

### Who made the logo?

* [@pyricau](https://github.com/pyricau) quickly made the [first version](https://github.com/square/leakcanary/blob/f0cc04dfbf3cca92a669f0d250034d410eb05816/assets/icon_512.png) of the logo. It was based on cliparts from [Android Asset Studio](http://romannurik.github.io/AndroidAssetStudio/icons-generic.html), mixed with the selection from a photo of a Canary. The exclamation mark means danger, the shield stands for protection, and the bird, well, is a canary.
* [@romainguy](https://github.com/romainguy) turned the ugly logo into [a nice vector asset](https://github.com/square/leakcanary/pull/36).
* [@flickator](https://github.com/flickator) designed [a much nicer logo](https://github.com/square/leakcanary/pull/1269) for LeakCanary 2.0!

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/logo-2.0.png" />
</p>

## License

    Copyright 2015 Square, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
