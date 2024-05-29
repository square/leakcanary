package leakcanary

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatetimeFormattedHeapDumpFileProvider(
  private val heapDumpDirectoryProvider: HeapDumpDirectoryProvider,
  private val dateProvider: () -> Date = { Date() },
  private val prefixProvider: () -> String = { "" },
  private val suffixProvider: () -> String = { "" }
) : HeapDumpFileProvider {

  private val timeFormatThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
    // Lint is drunk and thinks we use the pattern 'u'
    @Suppress("NewApi")
    override fun initialValue() =
      SimpleDateFormat(TIME_PATTERN, Locale.US)
  }

  override fun newHeapDumpFile(): File {
    val datetime = dateProvider()
    val formattedDatetime = timeFormatThreadLocal.get()!!.format(datetime)
    val fileName = "${prefixProvider()}$formattedDatetime${suffixProvider()}.hprof"
    return File(heapDumpDirectoryProvider.heapDumpDirectory(), fileName)
  }

  companion object {
    const val TIME_PATTERN = "yyyy-MM-dd_HH-mm-ss_SSS"
  }
}

