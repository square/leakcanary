package leakcanary

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import leakcanary.HeapAnalysisCondition.Result
import leakcanary.HeapAnalysisCondition.Result.StartAnalysis
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis
import leakcanary.internal.uiHandler
import java.io.BufferedReader
import java.io.FileReader

class MinimumElapsedSinceStartCondition(
  private val minElapsedSinceStartMillis: Long = 30_000
) : HeapAnalysisCondition() {

  private val postedRetry =
    Runnable { trigger.conditionChanged("enough time elapsed since app start") }

  private var enoughTimeElapsed = false

  override fun evaluate(): Result {
    if (enoughTimeElapsed) {
      return StartAnalysis
    }
    uiHandler.removeCallbacks(postedRetry)
    val elapsedMillisSinceStart = elapsedMillisSinceStart()
    return if (elapsedMillisSinceStart >= minElapsedSinceStartMillis) {
      enoughTimeElapsed = true
      StartAnalysis
    } else {
      val remainingMillis = minElapsedSinceStartMillis - elapsedMillisSinceStart
      uiHandler.postDelayed(postedRetry, remainingMillis)
      StopAnalysis("Not enough time elapsed since start, will retry in $remainingMillis ms")
    }
  }

  private fun elapsedMillisSinceStart() = if (Build.VERSION.SDK_INT >= 24) {
    SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
  } else {
    SystemClock.elapsedRealtime() - readProcessForkRealtimeMillis()
  }

  private fun readProcessForkRealtimeMillis(): Long {
    val myPid = Process.myPid()
    val ticksAtProcessStart = readProcessStartTicks(myPid)

    val ticksPerSecond = if (Build.VERSION.SDK_INT >= 21) {
      Os.sysconf(OsConstants._SC_CLK_TCK)
    } else {
      val tckConstant = try {
        Class.forName("android.system.OsConstants").getField("_SC_CLK_TCK").getInt(null)
      } catch (e: ClassNotFoundException) {
        Class.forName("libcore.io.OsConstants").getField("_SC_CLK_TCK").getInt(null)
      }
      val os = Class.forName("libcore.io.Libcore").getField("os").get(null)!!
      os::class.java.getMethod("sysconf", Integer.TYPE).invoke(os, tckConstant) as Long
    }
    return ticksAtProcessStart * 1000 / ticksPerSecond
  }

  // Benchmarked (with Jetpack Benchmark) on Pixel 3 running
  // Android 10. Median time: 0.13ms
  private fun readProcessStartTicks(pid: Int): Long {
    val path = "/proc/$pid/stat"
    val stat = BufferedReader(FileReader(path)).use { reader ->
      reader.readLine()
    }
    val fields = stat.substringAfter(") ")
        .split(' ')
    return fields[19].toLong()
  }
}