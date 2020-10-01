package leakcanary.internal

import android.util.Log
import shark.SharkLog.Logger

internal class DefaultCanaryLog : Logger {

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