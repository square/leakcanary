# Code Recipes

If you think a recipe might be missing or you're not sure that what you're trying to achieve is possible with the current APIs, please [file an issue](https://github.com/square/leakcanary/issues/new/choose). Your feedback help us make LeakCanary better for the entire community.

## Watching objects with a lifecycle

In your application, you may have other objects with a lifecycle, such as fragments, services, Dagger components, etc. Use [AppWatcher.objectWatcher](/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/object-watcher/) to watch instances that should be garbage collected:

```kotlin
class MyService : Service {

  // ...

  override fun onDestroy() {
    super.onDestroy()
    AppWatcher.objectWatcher.watch(this)
  }
}
```

## Configuration

To customize the detection of retained objects at runtime, update [AppWatcher.config](/api/leakcanary-object-watcher-android/leakcanary/-app-watcher/config/):

```
AppWatcher.config = AppWatcher.config.copy(watchFragmentViews = false)
```

To customize the heap dumping & analysis, update [LeakCanary.config](/api/leakcanary-android-core/leakcanary/-leak-canary/config/):

```
LeakCanary.config = LeakCanary.config.copy(retainedVisibleThreshold = 3)
```

The LeakCanary UI can be configured by overriding the following resources:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
  <public name="leak_canary_display_activity_label" type="string"/>
  <public name="leak_canary_heap_dump_toast" type="layout"/>
  <public name="leak_canary_icon" type="mipmap"/>
  <public name="leak_canary_add_dynamic_shortcut" type="bool"/>
  <public name="leak_canary_add_launcher_icon" type="bool"/>
</resources>
```

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

`com.squareup.leakcanary:leakcanary-android` should only be used in debug builds. It depends on `com.squareup.leakcanary:object-watcher-android` which you can use in production to track and count retained instances.

In your `build.gradle`:

```gradle
dependencies {
  implementation 'com.squareup.leakcanary:object-watcher-android:2.0-beta-1'
}
```

In your leak reporting code:
```kotlin
val retainedInstanceCount = AppWatcher.objectWatcher.retainedObjectCount
```

## Running LeakCanary in instrumentation tests

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

Create a custom [OnHeapAnalyzedListener](/api/leakcanary-android-core/leakcanary/-on-heap-analyzed-listener/) that delegates to [DefaultOnHeapAnalyzedListener](/api/leakcanary-android-core/leakcanary/-default-on-heap-analyzed-listener/): 

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

Set [LeakCanary.config.onHeapAnalyzedListener](/api/leakcanary-android-core/leakcanary/-leak-canary/-config/on-heap-analyzed-listener/):

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(onHeapAnalyzedListener = LeakUploader())
  }
}
```


## Matching known library leaks

Set [LeakCanary.Config.referenceMatchers](/api/leakcanary-android-core/leakcanary/-leak-canary/-config/reference-matchers/) to a list that builds on top of [AndroidReferenceMatchers.appDefaults](/api/shark-android/shark/-android-reference-matchers/app-defaults/):

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
  devDebugImplementation "com.squareup.leakcanary:leakcanary-android:${leakCanaryVersion}"
}
```
