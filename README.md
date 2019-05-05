# LeakCanary

A memory leak detection library for Android and Kotlin.

*“A small leak will sink a great ship.”* - Benjamin Franklin

<p align="center">
<img src="https://github.com/square/leakcanary/wiki/assets/screenshot-2.0.png"/>
</p>

## Getting started

Add LeakCanary to your `build.gradle`:

```gradle
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-1'
}
```

**That's it!** LeakCanary will automatically show a notification when an activity or fragment memory leak is detected in your debug builds.

Note: **LeakCanary 2 is in alpha**.
* Check out the [migration guide](https://github.com/square/leakcanary/wiki/Migrating-to-LeakCanary-2.0).
* Here is the [change log](https://github.com/square/leakcanary/blob/master/CHANGELOG.md#version-20-alpha-1-2019-04-23).
* To set up LeakCanary 1.6, go to the [1.6 Readme](https://github.com/square/leakcanary/blob/master/README-1.6.md).

## Presentations

* [LeakCanary, then what? Nuking Nasty Memory Leaks](https://www.youtube.com/watch?v=fhE--eTEW84)
* [Memory Leak Hunt](https://www.youtube.com/watch?v=KwArTJHLq5g), a live investigation.

## Fundamentals

### What is a Java memory leak?

A memory leak is a programming error that causes your application to keep a reference to an object that is no longer needed. As a result, the memory allocated for that object cannot be reclaimed, eventually leading to an OutOfMemoryError crash.

For instance, an Android activity instance is no longer needed after its `onDestroy()` method is called, and storing a reference to that activity in a static field would prevent it from being garbage collected.

### Why should I use LeakCanary?

Memory leaks are very common in Android apps. OutOfMemoryError is the top crasher for most apps on the play store, however that's usually not counted correctly. When memory is low the OutOfMemoryError can be thrown from anywhere in your code, which means every OOM has a different stacktrace and they're counted as different crashes.

When we first enabled LeakCanary in the Square Point Of Sale app, we were able to find and fix several leaks and reduced the OutOfMemoryError crash rate by **94%**.

### How does LeakCanary work?

* LeakSentry watches destroyed activities and destroyed fragments using weak references. You can also pass any instance that is no longer needed, e.g. a detached view.
* If the weak references aren't cleared, after waiting 5 seconds and running the GC, the activity and fragment instances are considered *retained*, and potentially leaking.
* When the number of retained instances reaches a threshold, LeakCanary dumps the Java heap into a `.hprof` file stored on the file system. The default threshold is 5 retained instances when the app is visible, 1 otherwise.
* LeakCanary parses the `.hprof` file and finds the chain of references that prevents retained instances from being garbage collected (**leak trace**). A leak trace is technically the *shortest strong reference path from GC Roots to retained instances*, but that's a mouthful.
* Once the leak trace is determined, LeakCanary uses its built in knowledge of the Android framework to deduct which instances in the leaktrace should be reachable vs not reachable. You can help LeakCanary by providing **Reachability inspectors** tailored to your own app.
* Using the reachability information, LeakCanary narrows down the reference chain to a sub chain of possible leak causes, and displays the result. Leaks are grouped by identical sub chain.

### How do I fix a memory leak?
To fix a memory leak, you need to look at the sub chain of possible leak causes and find which reference is causing the leak, i.e. which reference should have been cleared at the time of the leak. LeakCanary highlights with a red underline wave the references that are the possible causes of the leak.

If you cannot figure out a leak, **please do not file an issue**. Instead, create a [Stack Overflow question](http://stackoverflow.com/questions/tagged/leakcanary) using the *leakcanary* tag.

## Recipes

If you think a recipe might be missing or you're not sure that what you're trying to achieve is possible with the current APIs, please [file an issue](https://github.com/square/leakcanary/issues/new). Your feedback help us make LeakCanary better for the entire community.

### Watching any instance

```kotlin
class MyService : Service {

  // ...

  override fun onDestroy() {
    super.onDestroy()
    LeakSentry.refWatcher.watch(this)
  }

}
```

### Configuring LeakSentry & LeakCanary

LeakSentry is in charge of detecting retained instances. Its configuration can be updated at any time by replacing `LeakSentry.config`:
```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakSentry.config = LeakSentry.config.copy(watchFragmentViews = false)
  }
}
```

LeakCanary is in charge of dumping the heap and analyzing it. Its configuration can be updated at any time by replacing `LeakCanary.config`:

```kotlin
disableLeakCanaryButton.setOnClickListener {
  LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
}
```

### Counting retained instances in production

In your `build.gradle`:

```gradle
dependencies {
  implementation 'com.squareup.leakcanary:leaksentry:2.0-alpha-1'
}
```

In your leak reporting code:
```kotlin
val retainedInstanceCount = LeakSentry.refWatcher.retainedKeys.size
```

### Alternate setup with the old perflib heap parser

If you want to try LeakCanary 2.0 features with the battle tested perflib heap parser, use a different dependency:

```gradle
dependencies {
  // debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-1'
  debugImplementation 'com.squareup.leakcanary:leakcanary-android-perflib:2.0-alpha-1'
}
```

In your **debug** `Application` class:

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to Perflib for heap analysis.
      // You should not init your app in this process.
      return
    }
    super.onCreate()
  }
}
```

## FAQ

Note: the entries in this FAQ have **not been updated for LeakCanary 2 yet**.

* [Why should I use LeakCanary?](https://github.com/square/leakcanary/wiki/FAQ#why-should-i-use-leakcanary)
* [How does it work?](https://github.com/square/leakcanary/wiki/FAQ#how-does-it-work)
* [How do I fix a memory leak?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-fix-a-memory-leak)
* [How do I customize LeakCanary to my needs?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-customize-leakcanary-to-my-needs)
* [Where can I learn more?](https://github.com/square/leakcanary/wiki/FAQ#where-can-i-learn-more)
* [How do I copy the leak trace?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-copy-the-leak-trace)
* [Can a leak be caused by the Android SDK?](https://github.com/square/leakcanary/wiki/FAQ#can-a-leak-be-caused-by-the-android-sdk)
* [How can I dig beyond the leak trace?](https://github.com/square/leakcanary/wiki/FAQ#how-can-i-dig-beyond-the-leak-trace)
* [How do disable I LeakCanary in tests?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-disable-leakcanary-in-tests)
* [How do I fix build errors?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-fix-build-errors)
* [How many methods does LeakCanary add?](https://github.com/square/leakcanary/wiki/FAQ#how-many-methods-does-leakcanary-add)
* [How do I use the SNAPSHOT version?](https://github.com/square/leakcanary/wiki/FAQ#how-do-i-use-the-snapshot-version)
* [How can I be notified of new releases?](https://github.com/square/leakcanary/wiki/FAQ#how-can-i-be-notified-of-new-releases)
* [Who's behind LeakCanary?](https://github.com/square/leakcanary/wiki/FAQ#whos-behind-leakcanary)
* [Why is it called LeakCanary?](https://github.com/square/leakcanary/wiki/FAQ#why-is-it-called-leakcanary)
* [Who made the logo?](https://github.com/square/leakcanary/wiki/FAQ#who-made-the-logo)
* [Instant Run can trigger invalid leaks](https://github.com/square/leakcanary/wiki/FAQ#instant-run-can-trigger-invalid-leaks)
* [I know I have a leak. Why doesn't the notification show?](https://github.com/square/leakcanary/wiki/FAQ#i-know-i-have-a-leak-why-doesnt-the-notification-show)

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
