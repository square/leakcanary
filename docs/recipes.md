# Code Recipes

!!! bug
    If you think a recipe might be missing or you're not sure that what you're trying to achieve is possible with the current APIs, please [file an issue](https://github.com/square/leakcanary/issues/new/choose). Your feedback help us make LeakCanary better for the entire community.

## Watching objects with a lifecycle

In your application, you may have other objects with a lifecycle, such as fragments, services, Dagger components, etc. Use [AppWatcher.objectWatcher](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/object-watcher/) to watch instances that should be garbage collected:

```kotlin
class MyService : Service {

  // ...

  override fun onDestroy() {
    super.onDestroy()
    AppWatcher.objectWatcher.watch(this, "MyService received Service#onDestroy() callback")
  }
}
```

## Configuration

LeakCanary has a default configuration that should work well for most apps. You can also customize it to your needs. The LeakCanary configuration is held by two singleton objects (`AppWatcher` and `LeakCanary`) and can be updated at any time. Most developers configure LeakCanary in their **debug** [Application](https://developer.android.com/reference/android/app/Application) class:

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
  }
}
```


!!! info
    You can create a debug application class in your `src/debug/java` folder. Don't forget to also register it in `src/debug/AndroidManifest.xml`.

To customize the detection of retained objects at runtime, update [AppWatcher.config](/leakcanary/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/config/):

```
AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
```

To customize the heap dumping & analysis, update [LeakCanary.config](/leakcanary/api/leakcanary-android-core/leakcanary/-leak-canary/config/):

```
LeakCanary.config = LeakCanary.config.copy(retainedVisibleThreshold = 3)
```

The LeakCanary UI can be configured by overriding the following resources:

* `mipmap/leak_canary_icon` see [Icon and label](#icon-and-label)
* `string/leak_canary_display_activity_label` see [Icon and label](#icon-and-label)
* `bool/leak_canary_add_dynamic_shortcut` see [Disabling LeakCanary](#disabling-leakcanary)
* `bool/leak_canary_add_launcher_icon` see [Disabling LeakCanary](#disabling-leakcanary)
* `layout/leak_canary_heap_dump_toast` the layout for the toast shown when the heap is dumped

## Disabling LeakCanary

Sometimes it's necessary to disable LeakCanary temporarily, for example for a product demo or when running performance tests. You have different options, depending on what you're trying to achieve:

* Create a build variant that does not include the LeakCanary dependencies, see [Setting up LeakCanary for different product flavors](#setting-up-leakcanary-for-different-product-flavors).
* Disable the tracking of retained objects: `AppWatcher.config = AppWatcher.config.copy(enabled = false)`.
* Disable the heap dumping & analysis: `LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)`.
* Hide the leak display activity launcher icon: override `R.bool.leak_canary_add_launcher_icon` or call `LeakCanary.showLeakDisplayActivityLauncherIcon(false)`

!!! info
    When you set `AppWatcher.config.enabled` to false, `AppWatcher.objectWatcher` will stop creating weak references to destroyed objects.

	If instead you set `LeakCanary.Config.dumpHeap` to false, `AppWatcher.objectWatcher` will still keep track of retained objects, and LeakCanary will look for these objects when you change `LeakCanary.Config.dumpHeap` back to true.

## Counting retained instances in production

The `com.squareup.leakcanary:leakcanary-android` dependency should only be used in debug builds. It depends on `com.squareup.leakcanary:leakcanary-object-watcher-android` which you can use in production to track and count retained instances.

In your `build.gradle`:

```gradle
dependencies {
  implementation 'com.squareup.leakcanary:leakcanary-object-watcher-android:2.0-beta-5'
}
```

In your leak reporting code:
```kotlin
val retainedInstanceCount = AppWatcher.objectWatcher.retainedObjectCount
```

## Running LeakCanary in instrumentation tests

Running leak detection in UI tests means you can detect memory leaks automatically in Continuous Integration prior to those leaks being merged into the codebase. However, as LeakCanary runs with a 5 seconds delay and freezes the VM to take a heap dump, this can introduce flakiness to the UI tests. Therefore it is automatically disabled by setting `LeakCanary.config.dumpHeap` to `false` when JUnit is on the runtime classpath.

LeakCanary provides an artifact dedicated to detecting leaks in UI tests which provides a run listener that waits for the end of a test, and if the test succeeds then it look for retained objects, trigger a heap dump if needed and perform an analysis.

To set it up, add the `leakcanary-android-instrumentation` dependency to your instrumentation tests:

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
./gradlew leakcanary-android-sample:connectedCheck
```

You can extend `FailTestOnLeakRunListener` to customize the behavior.

## Icon and label

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

## Uploading to a server

You can change the default behavior to upload the analysis result to a server of your choosing.

Create a custom [OnHeapAnalyzedListener](/leakcanary/api/leakcanary-android-core/leakcanary/-on-heap-analyzed-listener/) that delegates to [DefaultOnHeapAnalyzedListener](/leakcanary/api/leakcanary-android-core/leakcanary/-default-on-heap-analyzed-listener/): 

```kotlin
class LeakUploader : OnHeapAnalyzedListener {

  val defaultListener = DefaultOnHeapAnalyzedListener.create()

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    TODO("Upload heap analysis to server")

    // Delegate to default behavior (notification and saving result)
    defaultListener.onHeapAnalyzed(heapAnalysis)
  }
}
```

Set [LeakCanary.config.onHeapAnalyzedListener](/leakcanary/api/leakcanary-android-core/leakcanary/-leak-canary/-config/on-heap-analyzed-listener/):

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(onHeapAnalyzedListener = LeakUploader())
  }
}
```

## Matching known library leaks

Set [LeakCanary.Config.referenceMatchers](/leakcanary/api/leakcanary-android-core/leakcanary/-leak-canary/-config/reference-matchers/) to a list that builds on top of [AndroidReferenceMatchers.appDefaults](/leakcanary/api/shark-android/shark/-android-reference-matchers/app-defaults/):

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(
        referenceMatchers = AndroidReferenceMatchers.appDefaults +
            AndroidReferenceMatchers.staticFieldLeak(
                className = "com.samsing.SomeSingleton",
                fieldName = "sContext",
                description = "SomeSingleton has a static field leaking a context.",
                patternApplies = {
                  manufacturer == "Samsing" && sdkInt == 26
                }
            )
    )
  }
}
```

## Ignoring specific activities or fragment classes

Sometimes a 3rd party library provides its own activities or fragments which contain a number of bugs leading to leaks of those specific 3rd party activities and fragments. You should push hard on that library to fix their memory leaks as it's directly impacting your application. That being said, until those are fixed, you have two options:

1. Add the specific leaks as known library leaks (see [Matching known library leaks](#matching-known-library-leaks)). LeakCanary will run when those leaks are detected and then report them as known library leaks.
2. Disable LeakCanary automatic activity or fragment watching (e.g. `AppWatcher.config = AppWatcher.config.copy(watchActivities = false)`) and then manually pass objects to `AppWatcher.objectWatcher.watch`.

## Identifying leaking objects and labeling objects

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    val addObjectIdLabel = ObjectInspector { reporter ->
      reporter.addLabel("Heap dump object id is ${reporter.heapObject.objectId}")
    }

    val singletonsInspector =
      AppSingletonInspector("com.example.MySingleton", "com.example.OtherSingleton")

    val mmvmInspector = ObjectInspector { reporter ->
      reporter.whenInstanceOf("com.mmvm.SomeViewModel") { instance ->
        val destroyedField = instance["com.mmvm.SomeViewModel", "destroyed"]!!
        if (destroyedField.value.asBoolean!!) {
          reportLeaking("SomeViewModel.destroyed is true")
        } else {
          reportNotLeaking("SomeViewModel.destroyed is false")
        }
      }
    }

    LeakCanary.config = LeakCanary.config.copy(
        objectInspectors = AndroidObjectInspectors.appDefaults +
            listOf(addObjectIdLabel, singletonsInspector, mmvmInspector)
    )
  }
}
```

## Running the LeakCanary analysis in a separate process

LeakCanary runs in your main app process. LeakCanary 2 is optimized to keep memory usage low while analysing and runs in a background thread with priority `Process.THREAD_PRIORITY_BACKGROUND`. If you find that LeakCanary is still using too much memory or impacting the app process performance, you can configure it to run the analysis in a separate process.

All you have to do is replace the `leakcanary-android` depedency with `leakcanary-android-process`:

```groovy
dependencies {
  // debugImplementation 'com.squareup.leakcanary:leakcanary-android:${version}'
  debugImplementation 'com.squareup.leakcanary:leakcanary-android-process:${version}'
}
```

You can call [LeakCanaryProcess.isInAnalyzerProcess](/leakcanary/api/leakcanary-android-process/leakcanary/-leak-canary-process/is-in-analyzer-process/) to check if your Application class is being created in the LeakCanary process. This is useful when configuring libraries like Firebase that may crash when running in an unexpected process.

## Setting up LeakCanary for different product flavors

You can setup LeakCanary to run in a specific product flavors of your app. For example, create:

```
android {
  flavorDimensions "default"
  productFlavors {
    prod {
      // ...
    }
    qa {
      // ...
    }
    dev {
      // ...
    }
  }
}
```

Then, define a custom configuration for the flavor for which you want to enable LeakCanary:

```
android {
  // ...
}
configurations {
    devDebugImplementation {}
}
```

You can now add the LeakCanary dependency for that configuration:

```
dependencies {
  devDebugImplementation "com.squareup.leakcanary:leakcanary-android:${version}"
}
```

## Extracting metadata from the heap dump

[LeakCanary.Config.metatadaExtractor](/leakcanary/api/leakcanary-android-core/leakcanary/-leak-canary/-config/metatada-extractor/) extracts metadata from a heap dump. The metadata is then available in `HeapAnalysisSuccess.metadata`. `LeakCanary.Config.metatadaExtractor` defaults to `AndroidMetadataExtractor` but you can replace it to extract additional metadata from the hprof.

For example, if you want to include the app version name in your heap analysis reports, you need to first store it in memory (e.g. in a static field) and then you can retrieve it in `MetadataExtractor`.

```kotlin
class DebugExampleApplication : ExampleApplication() {

  companion object {
    @JvmStatic
    lateinit var savedVersionName: String
  }

  override fun onCreate() {
    super.onCreate()

    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    savedVersionName = packageInfo.versionName

    LeakCanary.config = LeakCanary.config.copy(
        metatadaExtractor = MetadataExtractor { graph ->
          val companionClass =
            graph.findClassByName("com.example.DebugExampleApplication")!!

          val versionNameField = companionClass["savedVersionName"]!!
          val versionName = versionNameField.valueAsInstance!!.readAsJavaString()!!

          val defaultMetadata = AndroidMetadataExtractor.extractMetadata(graph)

          mapOf("App Version Name" to versionName) + defaultMetadata
        })
  }
}
```