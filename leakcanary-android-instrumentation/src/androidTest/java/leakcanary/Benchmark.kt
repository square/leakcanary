package leakcanary

import android.os.SystemClock
import leakcanary.Profiler.runWithMethodTracing
import shark.SharkLog

/**
 * Set of tools for benchmarking and tracing blocks of code
 */
object Benchmark {

  /**
   * Executes the given function [block] twice (without and with method tracing to SD card) and
   * returns the result of the function execution.
   * First execution of [block] is done to warm up code and load any necessary libs. Second
   * execution is measured with [runWithMethodTracing]
   */
  fun <T> benchmarkWithMethodTracing(block:() -> T): T {
    SharkLog.d { "Dry run to warm up the code." }
    block()
    SharkLog.d { "Run with sampling" }
    return runWithMethodTracing(block)
  }

  /**
   * Executes the given function [block] multiple times measuring the average execution time and
   * returns the result of the function execution.
   * Number of executions is [times] + 1, where 1 execution is done for code warm up and other
   * [times] executions are measured. Results of measurement will be outputted to LogCat at each
   * iteration and in the end of measurement.
   */
  fun <T> benchmarkCode(times: Int = 10, block:() -> T): T {
    // Warm-up run, no benchmarking
    val result = block()
    val measurements = mutableListOf<Long>()
    repeat(times) {
      val start = SystemClock.uptimeMillis()
      block()
      val end = SystemClock.uptimeMillis()
      SharkLog.d { "BenchmarkCode, iteration ${it + 1}/$times, duration ${end-start}" }
      measurements.add(end - start)
    }
    measurements.sort()
    val median: Double = if (times % 2 == 0) {
      (measurements[times / 2] + measurements[times / 2 - 1]).toDouble() / 2
    } else {
      measurements[times / 2].toDouble()
    }
    SharkLog.d { "BenchmarkCode complete, $times iterations. Durations (ms): median $median, " +
        "min ${measurements.first()}, max ${measurements.last()}" }
    return result
  }
}