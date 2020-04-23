package leakcanary

import android.os.Debug
import shark.SharkLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper class for working with Android Studio's Profiler
 */
internal object Profiler {
  private const val SLEEP_TIME_MILLIS = 1000L
  private const val SAMPLING_THREAD_NAME = "Sampling Profiler"

  /**
   * Wait until Profiler is attached and CPU Sampling is started.
   * Calling this on main thread can lead to ANR if you try to interact with UI while it's waiting for
   * profiler.
   * Note: only works with 'Sample Java Methods' profiling, won't work with 'Trace Java Methods'!
   */
  fun waitForSamplingStart() {
    SharkLog.d { "Waiting for sampling to start. Go to Profiler -> CPU -> Record" }
    sleepUntil { samplingThreadExists() }
    Thread.sleep(SLEEP_TIME_MILLIS) //Wait a bit more to ensure profiler started sampling
    SharkLog.d { "Sampling started! Proceeding..." }
  }

  /**
   * Wait until CPU Sampling stops.
   * Calling this on main thread can lead to ANR if you try to interact with UI while it's waiting for
   * profiler.
   */
  fun waitForSamplingStop() {
    SharkLog.d { "Waiting for sampling to stop. Go to Profiler -> CPU -> Stop recording" }
    sleepUntil { !samplingThreadExists() }
    SharkLog.d { "Sampling stopped! Proceeding..." }
  }

  /**
   * Executes the given function [block] with CPU sampling via Profiler and returns the result of
   * the function execution.
   * First, it awaits for Profiler to be attached at start of sampling, then executes [block]
   * and finally waits for sampling to stop. See [waitForSamplingStart] and [waitForSamplingStop]
   * for more details.
   */
  fun <T> runWithProfilerSampling(block: () -> T): T {
    waitForSamplingStart()
    val result = block()
    waitForSamplingStop()
    return result
  }

  private const val TRACES_FOLDER = "/sdcard/traces/"
  private const val TRACE_NAME_PATTERN = "yyyy-MM-dd_HH-mm-ss_SSS'.trace'"
  private const val BUFFER_SIZE = 50 * 1024 * 1024
  private const val TRACE_INTERVAL_US = 1000

  /**
   * Executes the given function [block] with method tracing to SD card and returns the result of
   * the function execution.
   * Tracing is performed with [Debug.startMethodTracingSampling] which uses sampling with
   * [TRACE_INTERVAL_US] microseconds interval.
   * Trace file will be stored in [TRACES_FOLDER] and can be pulled via `adb pull` command.
   * See Logcat output for an exact command to retrieve trace file
   */
  fun <T> runWithMethodTracing(block: () -> T): T {
    java.io.File(TRACES_FOLDER).mkdirs()
    val fileName = SimpleDateFormat(TRACE_NAME_PATTERN, Locale.US).format(Date())
    Debug.startMethodTracingSampling(
        "$TRACES_FOLDER$fileName",
        BUFFER_SIZE,
        TRACE_INTERVAL_US
    )
    val result = block()
    Debug.stopMethodTracing()
    SharkLog.d { "Method tracing complete! Run the following command to retrieve the trace:" }
    SharkLog.d { "adb pull $TRACES_FOLDER$fileName ~/Downloads/ " }
    return result
  }

  private inline fun sleepUntil(condition: () -> Boolean) {
    while (true) {
      if (condition()) return else Thread.sleep(SLEEP_TIME_MILLIS)
    }
  }

  private fun samplingThreadExists() = findThread(SAMPLING_THREAD_NAME) != null

  /**
   * Utility to get thread by its name; in case of multiple matches first one will be returned.
   */
  private fun findThread(threadName: String): Thread? {
    // Based on https://stackoverflow.com/a/1323480
    var rootGroup = Thread.currentThread().threadGroup
    while (rootGroup.parent != null) rootGroup = rootGroup.parent

    var threads = arrayOfNulls<Thread>(rootGroup.activeCount())
    while (rootGroup.enumerate(threads, true) == threads.size) {
      threads = arrayOfNulls(threads.size * 2)
    }
    return threads.firstOrNull { it?.name == threadName }
  }
}