package leakcanary

import leakcanary.CanaryLog.Logger
import java.io.PrintWriter
import java.io.StringWriter

class CLILogger : Logger {

  override fun d(
    message: String,
    vararg args: Any?
  ) {
    val formatted = if (args.isNotEmpty()) {
      String.format(message, *args)
    } else {
      message
    }
    println(formatted)
  }

  override fun d(
    throwable: Throwable?,
    message: String,
    vararg args: Any?
  ) {
    d(String.format(message, *args) + '\n' + getStackTraceString(throwable))
  }

  private fun getStackTraceString(throwable: Throwable?): String {
    if (throwable == null) {
      return ""
    }
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter, false)
    throwable.printStackTrace(printWriter)
    printWriter.flush()
    return stringWriter.toString()
  }
}