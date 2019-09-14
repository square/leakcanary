package leakcanary

import shark.SharkLog

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