package leakcanary

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.MemoryInfo
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileReader
import leakcanary.ProcessInfo.AvailableRam.BelowThreshold
import leakcanary.ProcessInfo.AvailableRam.LowRamDevice
import leakcanary.ProcessInfo.AvailableRam.Memory

interface ProcessInfo {

  val isImportanceBackground: Boolean

  val elapsedMillisSinceStart: Long

  fun availableDiskSpaceBytes(path: File): Long

  sealed class AvailableRam {
    object LowRamDevice : AvailableRam()
    object BelowThreshold : AvailableRam()
    class Memory(val bytes: Long) : AvailableRam()
  }

  fun availableRam(context: Context): AvailableRam

  @SuppressLint("NewApi")
  object Real : ProcessInfo {
    private val memoryOutState = RunningAppProcessInfo()
    private val memoryInfo = MemoryInfo()

    private val processStartUptimeMillis by lazy {
      Process.getStartUptimeMillis()
    }

    private val processForkRealtimeMillis by lazy {
      readProcessForkRealtimeMillis()
    }

    override val isImportanceBackground: Boolean
      get() {
        ActivityManager.getMyMemoryState(memoryOutState)
        return memoryOutState.importance >= RunningAppProcessInfo.IMPORTANCE_BACKGROUND
      }

    override val elapsedMillisSinceStart: Long
      get() = SystemClock.uptimeMillis() - processStartUptimeMillis

    @SuppressLint("UsableSpace")
    override fun availableDiskSpaceBytes(path: File) = path.usableSpace

    override fun availableRam(context: Context): AvailableRam {
      val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

      if (activityManager.isLowRamDevice) {
        return LowRamDevice
      } else {
        activityManager.getMemoryInfo(memoryInfo)

        return if (memoryInfo.lowMemory || memoryInfo.availMem <= memoryInfo.threshold) {
          BelowThreshold
        } else {
          val systemAvailableMemory = memoryInfo.availMem - memoryInfo.threshold

          val runtime = Runtime.getRuntime()
          val appUsedMemory = runtime.totalMemory() - runtime.freeMemory()
          val appAvailableMemory = runtime.maxMemory() - appUsedMemory

          val availableMemory = systemAvailableMemory.coerceAtMost(appAvailableMemory)
          Memory(availableMemory)
        }
      }
    }

    /**
     * See https://dev.to/pyricau/android-vitals-when-did-my-app-start-24p4#process-fork-time
     */
    private fun readProcessForkRealtimeMillis(): Long {
      val myPid = Process.myPid()
      val ticksAtProcessStart = readProcessStartTicks(myPid)

      val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK)
      return ticksAtProcessStart * 1000 / ticksPerSecond
    }

    // Benchmarked (with Jetpack Benchmark) on Pixel 3 running
    // Android 10. Median time: 0.13ms
    private fun readProcessStartTicks(pid: Int): Long {
      val path = "/proc/$pid/stat"
      val stat = FileReader(path).buffered().use { reader ->
        reader.readLine()
      }
      val fields = stat.substringAfter(") ")
        .split(' ')
      return fields[19].toLong()
    }
  }
}
