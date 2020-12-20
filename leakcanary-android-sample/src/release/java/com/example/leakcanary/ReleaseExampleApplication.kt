package com.example.leakcanary

import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.util.Log
import leakcanary.BackgroundTrigger
import leakcanary.HeapAnalysisClient
import leakcanary.HeapAnalysisConfig
import leakcanary.HeapAnalysisJob.Result
import leakcanary.ScreenOffTrigger
import shark.SharkLog
import shark.SharkLog.Logger
import java.util.concurrent.Executors

class ReleaseExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    // Useful to debug in release builds. Don't use in real builds.
    SharkLog.logger = object : Logger {
      override fun d(message: String) {
        if (message.length < 4000) {
          Log.d("LeakCanary", message)
        } else {
          message.lines().forEach { line ->
            Log.d("LeakCanary", line)
          }
        }
      }

      override fun d(
        throwable: Throwable,
        message: String
      ) {
        d("$message\n${Log.getStackTraceString(throwable)}")
      }
    }

    val analysisClient = HeapAnalysisClient(
      heapDumpDirectoryProvider = { filesDir },
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