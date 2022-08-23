### LeakCanary for releases

Fixing leaks found in debug builds helps reduce `Application Not Responding` freezes and
`OutfOfMemoryError` error crashes, but only scratches the surface of all the leaks that can happen.
For the leaks that are found in debug builds, it's hard to determine which leaks to fix first.

This situation is very similar to debug crashes, where we are
often unable to make an accurate assessment of their future impact in a production environment nor
find all crashes that will happen in production. For crashes, apps typically monitor a crash rate by
having a release crash reporting pipeline, with counts to prioritize fixes.

LeakCanary for releases exposes APIs to run a heap analysis in release builds, in production.

!!! danger
    Everything about this is experimental. Running a heap analysis in production is not a very
    common thing to do, and we're still learning and experimenting with this. Also, both the
    artifact name and the APIs may change.

## Getting started

LeakCanary provides an artifact dedicated to detecting leaks in release builds:

```groovy
dependencies {
  // LeakCanary for releases
  releaseImplementation 'com.squareup.leakcanary:leakcanary-android-release:{{ leak_canary.release }}'
  // Optional: detect retained objects. This helps but is not required.
  releaseImplementation 'com.squareup.leakcanary:leakcanary-object-watcher-android:{{ leak_canary.release }}'
}
```

Here's a code example that runs a heap analysis when the screen is turned off or the app enters background, checking first if a [Firebase Remote Config](https://firebase.google.com/products/remote-config) flag is turned on, and uploading the result to Bugsnag:

```kotlin
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import leakcanary.BackgroundTrigger
import leakcanary.HeapAnalysisClient
import leakcanary.HeapAnalysisConfig
import leakcanary.HeapAnalysisInterceptor
import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.HeapAnalysisJob
import leakcanary.HeapAnalysisJob.Result.Done
import leakcanary.ScreenOffTrigger

class ReleaseExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()

    // Delete any remaining heap dump (if we crashed)
    analysisExecutor.execute {
      analysisClient.deleteHeapDumpFiles()
    }

    // Starts heap analysis on background importance
    BackgroundTrigger(
      application = this,
      analysisClient = analysisClient,
      analysisExecutor = analysisExecutor,
      analysisCallback = analysisCallback
    ).start()

    // Starts heap analysis when screen off
    ScreenOffTrigger(
      application = this,
      analysisClient = analysisClient,
      analysisExecutor = analysisExecutor,
      analysisCallback = analysisCallback
    ).start()
  }

  /**
   * Call this to trigger heap analysis manually, e.g. from
   * a help button.
   *
   * This method returns a `HeapAnalysisJob` on which you can
   * call `HeapAnalysisJob.cancel()` at any time.
   */
  fun triggerHeapAnalysisNow(): HeapAnalysisJob {
    val job = analysisClient.newJob()
    analysisExecutor.execute {
      val result = job.execute()
      analysisCallback(result)
    }
    return job
  }

  private val analysisClient by lazy {
    HeapAnalysisClient(
      // Use private app storage. cacheDir is never backed up which is important.
      heapDumpDirectoryProvider = { cacheDir },
      // stripHeapDump: remove all user data from hprof before analysis.
      config = HeapAnalysisConfig(stripHeapDump = true),
      // Default interceptors may cancel analysis for several other reasons.
      interceptors = listOf(flagInterceptor) + HeapAnalysisClient.defaultInterceptors(this)
    )
  }

  // Cancels heap analysis if "heap_analysis_flag" is false.
  private val flagInterceptor = object : HeapAnalysisInterceptor {
    val remoteConfig by lazy { FirebaseRemoteConfig.getInstance() }

    override fun intercept(chain: Chain): HeapAnalysisJob.Result {
      if (remoteConfig.getBoolean("heap_analysis_flag")) {
        chain.job.cancel("heap_analysis_flag false")
      }
      return chain.proceed()
    }
  }

  private val analysisExecutor = Executors.newSingleThreadExecutor {
    thread(start = false, name = "Heap analysis executor") {
      android.os.Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)
      it.run()
    }
  }

  private val analysisCallback: (HeapAnalysisJob.Result) -> Unit = { result ->
    if (result is Done) {
      uploader.upload(result.analysis)
    }
  }

  private val uploader by lazy {
    BugsnagLeakUploader(this@ReleaseExampleApplication)
  }
}
```

Here's the `BugsnagLeakUploader`:

--8<-- "docs/snippets/bugsnag-uploader.md"
