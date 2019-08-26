package shark

import shark.SharkLog.Logger
import java.io.PrintWriter
import java.io.StringWriter

class CLILogger : Logger {

  override fun d(message: String) {
    println(message)
  }

  override fun d(throwable: Throwable, message: String) {
    d("$message\n${getStackTraceString(throwable)}")
  }

  private fun getStackTraceString(throwable: Throwable): String {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter, false)
    throwable.printStackTrace(printWriter)
    printWriter.flush()
    return stringWriter.toString()
  }
}