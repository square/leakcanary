package com.example.leakcanary

import android.util.Log
import leakcanary.ConditionalHeapAnalyzer
import leakcanary.ConditionalHeapAnalyzer.Result
import shark.SharkLog
import shark.SharkLog.Logger

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
      override fun d(throwable: Throwable, message: String) {
        d("$message\n${Log.getStackTraceString(throwable)}")
      }
    }

    val listener = object : ConditionalHeapAnalyzer.Listener {
      override fun onConditionsUpdate(result: Result) {
        Log.d("LeakCanary", result.toString())
      }
    }
    val analyzer = ConditionalHeapAnalyzer(this, listener = listener)
    analyzer.start()
  }
}