package leakcanary.internal

import android.util.Log
import leakcanary.CanaryLog.Logger

class DefaultCanaryLog : Logger {

  override fun d(
    message: String,
    vararg args: Any?
  ) {
    val formatted = String.format(message, *args)
    if (formatted.length < 4000) {
      Log.d("LeakCanary", formatted)
    } else {
      val lines = formatted.split("\n".toRegex())
          .toTypedArray()
      for (line in lines) {
        Log.d("LeakCanary", line)
      }
    }
  }

  override fun d(
    throwable: Throwable?,
    message: String,
    vararg args: Any?
  ) {
    d(
        String.format(message, *args) + '\n'.toString() + Log.getStackTraceString(
            throwable
        )
    )
  }
}