LeakCanary 2 is a major rewrite. High level changes:

* New heap analyzer, reimplemented from scratch to use 10 times less memory ([see Shark](shark.md)).
* APIs updated to simplify configuration and provide access to the new heap analyzer.
* Internals rewritten to 100% Kotlin.
* Multiple leaks detected in one analysis, grouped per leak type

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
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.release }}'
}
```

### Worth noting

* The `leakcanary-android-no-op` artifact is gone. If you have compile errors, see below.
  * **Question**: if there's no no-op anymore, how do I ensure none of this runs during release builds?
  * **Answer**: as long as you add `leakcanary-android` as `debugImplementation`, there won't be any code referencing LeakCanary in your release builds.
* LeakCanary does not depend on the support library anymore, and it doesn't depend on AndroidX either.
* Detection of AndroidX fragments is automatic if you have the AndroidX fragments dependency.

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
val refWatcher: RefWatcher = LeakCanary.installedRefWatcher()
```

### Now

```kotlin
val objectWatcher: ObjectWatcher = AppWatcher.objectWatcher
```

## Compile errors because RefWatcher is used in release code

If you were using `RefWatcher` in non debug code, you now get a compile error because the no-op artifact is gone. [ObjectWatcher](/leakcanary/api/leakcanary-object-watcher/leakcanary/-object-watcher/) now lives in the `object-watcher` artifact, which is suitable for release builds. You have two options:

### Option 1: Add `object-watcher-android` to release builds.

```groovy
dependencies {
  implementation 'com.squareup.leakcanary:leakcanary-object-watcher-android:{{ leak_canary.release }}'
}
```

* It will automatically keep weak references to destroyed activities, fragments, and any instance you pass to [AppWatcher.objectWatcher](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/object-watcher/).
* It will not trigger heap dumps or anything else that LeakCanary does.
* It's very little code and should have a no impact on your release app.
* You can use it to count how many objects are retained, for example to add metadata to OutOfMemoryError crashes:

```kotlin
val retainedObjectCount = AppWatcher.objectWatcher.retainedObjectCount
```

### Option 2: Make your own `ObjectWatcher` interface

```kotlin
// In shared code
interface MaybeObjectWatcher {
  fun watch(watchedObject: Any, description: String)

  object None : MaybeObjectWatcher {
    override fun watch(watchedObject: Any, description: String) {
    }
  }
}

// In debug code
class RealObjectWatcher : MaybeObjectWatcher {
  override fun watch(watchedObject: Any, description: String) {
    AppWatcher.objectWatcher.watch(watchedObject, description)
  }
}
```

Use `MaybeObjectWatcher.None` in release code and `RealObjectWatcher` in debug code.

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

AppWatcher is in charge of detecting retained objects. Its configuration can be updated at any time by replacing [AppWatcher.config](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/config/):

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
  }
}
```

LeakCanary is in charge of taking heap dumps and analyzing them. Its configuration can be updated at any time by replacing [LeakCanary.config](/leakcanary/api/leakcanary-android-core/leakcanary/-leak-canary/config/):

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
class LeakUploader : OnHeapAnalyzedListener {

  val defaultListener = DefaultOnHeapAnalyzedListener.create()

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    TODO("Upload heap analysis to server")

    // Delegate to default behavior (notification and saving result)
    defaultListener.onHeapAnalyzed(heapAnalysis)
  }
}

class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(
        onHeapAnalyzedListener = LeakUploader()
    )
  }
}
```

### Matching known library leaks

### Before

```java
ExcludedRefs excludedRefs = AndroidExcludedRefs.createAppDefaults()
    .staticField("com.samsing.SomeSingleton", "sContext")
    .build();
RefWatcher refWatcher = LeakCanary.refWatcher(this)
  .excludedRefs(excludedRefs)
  .buildAndInstall();
}
```

### Now

```kotlin
LeakCanary.config = LeakCanary.config.copy(
    referenceMatchers = AndroidReferenceMatchers.appDefaults +
        AndroidReferenceMatchers.staticFieldLeak(
            "com.samsing.SomeSingleton",
            "sContext"
        )
)
```

!!! info
    There is no equivalent API to `ExcludedRefs.Builder.clazz()` because it led to abuses. Instead see [Ignoring specific activities or fragment classes](recipes.md#ignoring-specific-activities-or-fragment-classes).

## Public API packages

### Before

All public APIs were in `com.squareup.leakcanary.*`

### Now

All public APIs are in `leakcanary.*`