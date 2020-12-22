package com.example.leakcanary

import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.util.Log
import leakcanary.BackgroundTrigger
import leakcanary.HeapAnalysisClient
import leakcanary.HeapAnalysisConfig
import leakcanary.HeapAnalysisJob.Result
import leakcanary.LogcatSharkLog
import leakcanary.LogcatSharkLog.Companion
import leakcanary.ScreenOffTrigger
import shark.SharkLog
import shark.SharkLog.Logger
import java.util.concurrent.Executors

class ReleaseExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    // Useful to debug in release builds. Don't use in real builds.
    LogcatSharkLog.install()

    val analysisClient = HeapAnalysisClient(
      heapDumpDirectoryProvider = { cacheDir },
      config = HeapAnalysisConfig(),
      interceptors = HeapAnalysisClient.defaultInterceptors(this)
    )

    val analysisExecutor = Executors.newSingleThreadExecutor {
      Thread {
        android.os.Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)
        it.run()
      }.apply {
        name = "Heap analysis executor"
      }
    }
    analysisExecutor.execute {
      analysisClient.deleteHeapDumpFiles()
    }
    val analysisCallback: (Result) -> Unit = { result ->
      SharkLog.d { "$result" }
    }
    BackgroundTrigger(
      application = this,
      analysisClient = analysisClient,
      analysisExecutor = analysisExecutor,
      analysisCallback = analysisCallback
    ).start()

    ScreenOffTrigger(
      application = this,
      analysisClient = analysisClient,
      analysisExecutor = analysisExecutor,
      analysisCallback = analysisCallback
    ).start()
  }
}