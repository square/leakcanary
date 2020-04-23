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
    var total = 0L
    repeat(times) {
      val start = SystemClock.uptimeMillis()
      block()
      val end = SystemClock.uptimeMillis()
      SharkLog.d { "BenchmarkCode, iteration ${it + 1}/$times, duration ${end-start}" }
      total += (end-start)
    }
    SharkLog.d { "BenchmarkCode complete. Iterations: $times, average duration ${total/times} ms" }
    return result
  }
}