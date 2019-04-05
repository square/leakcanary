package leakcanary

import android.util.Log

class CanaryLog private constructor() {

  interface Logger {
    fun d(
      message: String,
      vararg args: Any?
    )

    fun d(
      throwable: Throwable?,
      message: String,
      vararg args: Any?
    )
  }

  private class DefaultLogger internal constructor() : Logger {

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
      d(String.format(message, *args) + '\n'.toString() + Log.getStackTraceString(throwable))
    }
  }

  init {
    throw AssertionError()
  }

  companion object {

    @Volatile private var logger: Logger? =
      DefaultLogger()

    fun setLogger(logger: Logger?) {
      Companion.logger = logger
    }

    fun d(
      message: String,
      vararg args: Any?
    ) {
      // Local variable to prevent the ref from becoming null after the null check.
      val logger = logger ?: return
      logger.d(message, *args)
    }

    fun d(
      throwable: Throwable?,
      message: String,
      vararg args: Any?
    ) {
      // Local variable to prevent the ref from becoming null after the null check.
      val logger = logger ?: return
      logger.d(throwable, message, *args)
    }
  }
}
