# Uploading analysis results

You can add an `EventListener` to upload the analysis result to a server of your choosing:

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    val analysisUploadListener = EventListener { event ->
      if (event is HeapAnalysisSucceeded) {
        val heapAnalysis = event.heapAnalysis
        TODO("Upload heap analysis to server")
      }
    }

    LeakCanary.config = LeakCanary.config.run {
      copy(eventListeners = eventListeners + analysisUploadListener)
    }
  }
}
```

## Uploading to Bugsnag

A leak trace has a lot in common with a stack trace, so if you lack the engineering resources to
build a backend for LeakCanary, you can instead upload leak traces to a crash reporting backend.
The client needs to support grouping via custom client-side hashing as well as custom metadata with
support for newlines.

!!! info
    As of this writing, the only known library suitable for uploading leaks is the Bugsnag client.
    If you managed to make it work with another library, please [file an issue](https://github.com/square/leakcanary/issues/new/choose).

Create a [Bugsnag account](https://app.bugsnag.com/user/new/), create a new project for leak
reporting and grab an **API key**. Make sure the app has the `android.permission.INTERNET`
permission then add the [latest version](https://docs.bugsnag.com/platforms/android/) of the
Bugsnag Android client library to `build.gradle`:

```groovy
dependencies {
  // debugImplementation because LeakCanary should only run in debug builds.
  debugImplementation 'com.squareup.leakcanary:leakcanary-android:{{ leak_canary.release }}'
  debugImplementation "com.bugsnag:bugsnag-android:$bugsnagVersion"
}
```

!!! info
    If you're only using Bugsnag for uploading leaks, then you do not need to set up the Bugsnag
    Gradle plugin or to configure the API key in your app manifest.

Create a new `BugsnagLeakUploader`:

--8<-- "docs/snippets/bugsnag-uploader.md"

Then add an `EventListener` to upload the analysis result to Bugsnag:

```kotlin
class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    LeakCanary.config = LeakCanary.config.copy(
        onHeapAnalyzedListener = BugsnagLeakUploader(applicationContext = this)
    )
  }
}
```

You should start seeing leaks reported into Bugsnag, grouped by their leak signature:

![list](images/bugsnag-list.png)

The `LEAK` tab contains the leak trace:

![leak](images/bugsnag-leak.png)
