package leakcanary

import android.app.ActivityManager
import android.app.ActivityManager.MemoryInfo
import android.app.Application
import android.content.Context
import android.os.Build
import leakcanary.internal.uiHandler

class MinimumMemoryCondition(
  private val application: Application,
  private val minRequiredAvailableMemoryBytes: Long = 100_000_000,
  private val tryAgainDelayMillis: Long = 30_000
) : HeapAnalysisCondition() {

  private val postedRetry = Runnable {
    trigger.conditionChanged(
        "$tryAgainDelayMillis ms passed since last memory check"
    )
  }

  private val memoryInfo = MemoryInfo()

  override fun evaluate(): Result {
    uiHandler.removeCallbacks(postedRetry)
    val activityManager = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    if (Build.VERSION.SDK_INT >= 19 &&  activityManager.isLowRamDevice) {
      return Result.StopAnalysis("low ram device")
    }
    activityManager.getMemoryInfo(memoryInfo)

    if (memoryInfo.lowMemory || memoryInfo.availMem <= memoryInfo.threshold) {
      return Result.StopAnalysis("low memory")
    }
    val systemAvailableMemory = memoryInfo.availMem - memoryInfo.threshold


    val runtime = Runtime.getRuntime()
    val appUsedMemory = runtime.totalMemory() - runtime.freeMemory()
    val appAvailableMemory = runtime.maxMemory() - appUsedMemory

    val availableMemory = systemAvailableMemory.coerceAtMost(appAvailableMemory)
    return if (availableMemory >= minRequiredAvailableMemoryBytes) {
      Result.StartAnalysis
    } else {
      uiHandler.postDelayed(postedRetry, tryAgainDelayMillis)
      Result.StopAnalysis(
          "Not enough free memory: available $availableMemory < min $minRequiredAvailableMemoryBytes"
      )
    }
  }

}