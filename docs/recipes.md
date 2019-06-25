# Code Recipes

If you think a recipe might be missing or you're not sure that what you're trying to achieve is possible with the current APIs, please [file an issue](https://github.com/square/leakcanary/issues/new/choose). Your feedback help us make LeakCanary better for the entire community.

## Configuring LeakSentry

LeakSentry can be configured by replacing `LeakSentry.config`:
```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakSentry.config = LeakSentry.config.copy(watchFragmentViews = false)
  }
}
```

## Configuring LeakCanary

LeakCanary can be configured by replacing `LeakCanary.config`:

```kotlin
disableLeakCanaryButton.setOnClickListener {
  LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
}
```

## Watching objects with a lifecycle

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

## Counting retained instances in production

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
./gradlew leakcanary-sample:connectedCheck
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


## Identifying 3rd party leaks as "won't fix"

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

## Identifying leaking instances and labeling instances

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