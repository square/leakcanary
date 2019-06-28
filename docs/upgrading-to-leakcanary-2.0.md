## Dependencies

### Before

```groovy
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:1.6.3'
  releaseImplementation 'com.squareup.leakcanary:leakcanary-android-no-op:1.6.3'
  // Optional, if you use support library fragments:
  debugImplementation 'com.squareup.leakcanary:leakcanary-support-fragment:1.6.3'
}
```

### Now

```groovy
dependencies {
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.0-alpha-2'
}
```

### Worth noting

* The `leakcanary-android-no-op` artifact is gone. If you have compile errors, see below.
  * **Question**: if there's no no-op anymore, how do I ensure none of this runs during release builds?
  * **Answer**: as long as you add `leakcanary-android` as `debugImplementation`, there won't be any code referencing LeakCanary in your release builds.
* LeakCanary now **depends on AndroidX** instead of the support library.
* Detection of AndroidX fragments is now automatic if you have the AndroidX fragments dependency.

## Default setup code

### Before

```java
public class ExampleApplication extends Application {

  @Override public void onCreate() {
    super.onCreate();
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to LeakCanary for heap analysis.
      // You should not init your app in this process.
      return;
    }
    LeakCanary.install(this);
    // Normal app init code...
  }
}
```

### Now

There is no more code for default setup.

### Worth noting

* LeakCanary auto installs itself
* LeakCanary analysis now runs in the main process so there is no need to call `LeakCanary.isInAnalyzerProcess()`.

## Retrieve the RefWatcher

### Before

```kotlin
val refWatcher = LeakCanary.installedRefWatcher()
```

### Now

```kotlin
val refWatcher = LeakSentry.refWatcher
```

## Compile errors because RefWatcher is used in release code

If you were using `RefWatcher` in non debug code, you now get a compile error because the no-op artifact is gone. `RefWatcher` now lives in the `leaksentry` artifact, which is suitable for production. You have two options:

### Option 1: Add `leaksentry` to release builds.

```groovy
dependencies {
  implementation 'com.squareup.leakcanary:leaksentry:2.0-alpha-2'
}
```

* It will automatically keep weak references on activities, fragments, and any instance you pass to `RefWatcher`.
* It will not trigger heap dumps or anything else that LeakCanary does.
* It's very little code and should have a no impact on your release app.
* You can use it to count how many instances are retained, for instance to add metadata to OutOfMemoryError crashes:

```kotlin
val retainedInstanceCount = LeakSentry.refWatcher.retainedInstanceCount
```

### Option 2: Make your own `RefWatcher` interface

```kotlin
// In shared code
interface MaybeRefWatcher {
  fun watch(watchedInstance: Any)

  object None : MaybeRefWatcher {
    override fun watch(watchedInstance: Any) {
    }
  }
}

// In debug code
class RealRefWatcher : MaybeRefWatcher {
  override fun watch(watchedInstance: Any) {
    LeakSentry.refWatcher.watch(watchedInstance)
  }
}
```

Use MaybeRefWatcher.None in release code and RealRefWatcher in debug code.

## Configuring LeakCanary

### Before

```java
public class DebugExampleApplication extends ExampleApplication {

  @Override protected void installLeakCanary() {
    RefWatcher refWatcher = LeakCanary.refWatcher(this)
      .watchActivities(false)
      .buildAndInstall();
  }
}
```

### Now

LeakSentry is in charge of detecting memory leaks. Its configuration can be updated at any time by replacing `LeakSentry.config`:
```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakSentry.config = LeakSentry.config.copy(watchFragmentViews = false)
  }
}
```

LeakCanary is in charge of taking heap dumps and analyzing them. Its configuration can be updated at any time by replacing `LeakCanary.config`:

```kotlin
disableLeakCanaryButton.setOnClickListener {
  LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
}
```

## Running LeakCanary in instrumentation tests

### Before

In your `build.gradle` file:

```groovy
dependencies {
  androidTestImplementation "com.squareup.leakcanary:leakcanary-android-instrumentation:${leakCanaryVersion}"
}

android {
  defaultConfig {
    // ...

    testInstrumentationRunner "android.support.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArgument "listener", "com.squareup.leakcanary.FailTestOnLeakRunListener"
  }
}
```

In your test `Application` class:

```java
public class InstrumentationTestExampleApplication extends DebugExampleApplication {
  @Override protected void installLeakCanary() {
    InstrumentationLeakDetector.instrumentationRefWatcher(this)
      .buildAndInstall();
  }
}
```

### Now

In your `build.gradle` file:

```groovy
dependencies {
  androidTestImplementation "com.squareup.leakcanary:leakcanary-android-instrumentation:${leakCanaryVersion}"
}

android {
  defaultConfig {
    // ...

    testInstrumentationRunner "android.support.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArgument "listener", "leakcanary.FailTestOnLeakRunListener"
  }
}
```

No code is necessary.

## Analysis listener / uploading to a server

### Before


```java
public class LeakUploadService extends DisplayLeakService {
  @Override protected void afterDefaultHandling(HeapDump heapDump, AnalysisResult result, String leakInfo) {
    // TODO Upload result to server
  }
}
```

```java
RefWatcher refWatcher = LeakCanary.refWatcher(this)
  .listenerServiceClass(LeakUploadService.class);
  .buildAndInstall();
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    >
  <application android:name="com.example.DebugExampleApplication">
    <service android:name="com.example.LeakUploadService" />
  </application>
</manifest>
```

### Now

```Kotlin
LeakCanary.config = LeakCanary.config.copy(
    analysisResultListener = { application, heapAnalysis ->
      // TODO Upload result to server
      DefaultAnalysisResultListener(application, heapAnalysis)
    }
)
```

### Marking known framework leaks as won't fix

### Before

```java
ExcludedRefs excludedRefs = AndroidExcludedRefs.createAppDefaults()
    .instanceField("com.example.ExampleClass", "exampleField")
    .build();
RefWatcher refWatcher = LeakCanary.refWatcher(this)
  .excludedRefs(excludedRefs)
  .buildAndInstall();
}
```

### Now

```kotlin
LeakCanary.config = LeakCanary.config.copy(
  exclusionsFactory = { parser ->
    val build = BuildMirror.readFromHprof(parser)
    val exclusions =
      AndroidExcludedRefs.exclusionsMatchingBuild(AndroidExcludedRefs.appDefaults, build)
          .toMutableList()
    exclusions += Exclusion(
        type = InstanceFieldExclusion("com.example.ExampleClass", "exampleField")
    )
    exclusions
  }
)
```

## Public API packages

### Before

All public APIs were in `com.squareup.leakcanary.*`

### Now

All public APIs are in `leakcanary.*`