package leakcanary.internal

import android.util.Log
import shark.SharkLog.Logger

internal class DefaultCanaryLog : Logger {

  override fun d(
    message: String,
    vararg args: Any?
  ) {
    val formatted = if (args.isNotEmpty()) String.format(message, *args) else message

    if (formatted.length < 4000) {
      Log.d("LeakCanary", formatted)
    } else {
      formatted.split(NEW_LINE_REGEX).forEach {line ->
        Log.d("LeakCanary", line)
      }
    }
  }

  override fun d(
    throwable: Throwable,
    message: String,
    vararg args: Any?
  ) {
    d(
        String.format(message, *args) + '\n'.toString() + Log.getStackTraceString(
            throwable
        )
    )
  }

  companion object {
    val NEW_LINE_REGEX = "\n".toRegex()
  }
}