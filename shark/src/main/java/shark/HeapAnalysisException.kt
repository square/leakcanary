package shark

import java.io.PrintWriter
import java.io.StringWriter

class HeapAnalysisException(cause: Throwable) : RuntimeException(cause) {

  override fun toString(): String {
    val stringWriter = StringWriter()
    cause!!.printStackTrace(PrintWriter(stringWriter))
    return stringWriter.toString()
  }

  companion object {
    private const val serialVersionUID: Long = -2522323377375290608
  }
}