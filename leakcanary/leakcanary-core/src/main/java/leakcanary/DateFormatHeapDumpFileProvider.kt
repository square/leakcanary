package leakcanary

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DateFormatHeapDumpFileProvider(
  private val heapDumpDirectory: File,
  private val dateProvider: () -> Date = { Date() },
  prefix: String = "",
  suffix: String = ""
) : HeapDumpFileProvider {

  init {
    heapDumpDirectory.apply {
      mkdirs()
      check(exists()) {
        "Expected heap dump folder to exist: $absolutePath"
      }
    }
  }

  private val dateFormatPattern =
    "${escape(prefix)}$TIME_PATTERN${escape("$suffix.hprof")}"

  private val timeFormatThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
    // Lint is drunk and thinks we use the pattern 'u'
    @Suppress("NewApi")
    override fun initialValue() =
      SimpleDateFormat(dateFormatPattern, Locale.US)
  }

  override fun newHeapDumpFile(): File {
    val date = dateProvider()
    val fileName = timeFormatThreadLocal.get()!!.format(date)
    return File(heapDumpDirectory, fileName)
  }

  private fun escape(string: String) = if (string != "") {
    "'$string'"
  } else ""

  companion object {
    const val TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss_SSS"
  }
}

fun HeapDumpFileProvider.Companion.datetimeFormatted(
  directory: File,
  prefix: String = "",
  suffix: String = "",
  dateProvider: () -> Date = { Date() },
): HeapDumpFileProvider {
  return DateFormatHeapDumpFileProvider(
    heapDumpDirectory = directory,
    dateProvider = dateProvider,
    prefix = prefix,
    suffix = suffix
  )
}
