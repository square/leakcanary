# Getting started

!!! info
    To upgrade from LeakCanary *1.6*, follow the [upgrade guide](upgrading-to-leakcanary-2.0.md).

To use LeakCanary, add the `leakcanary-android` dependency to your app's `build.gradle` file:

```groovy
dependencies {
  // debugImplementation because LeakCanary should only run in debug builds.
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.release }}'
}
```

**That's it, there is no code change needed!** Confirm that LeakCanary is running on startup by filtering on the `LeakCanary` tag in [Logcat](https://developer.android.com/studio/command-line/logcat):

```
D LeakCanary: Installing AppWatcher
```

LeakCanary automatically detects leaks for the following objects:

* destroyed `Activity` instances
* destroyed `Fragment` instances
* destroyed fragment `View` instances
* cleared `ViewModel` instances

What's next? Learn the [Fundamentals](fundamentals.md)!