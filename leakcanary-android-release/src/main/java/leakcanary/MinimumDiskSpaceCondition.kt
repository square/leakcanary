package leakcanary

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.os.StatFs
import leakcanary.HeapAnalysisCondition.Result.StartAnalysis
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis
import leakcanary.internal.uiHandler
import java.util.concurrent.TimeUnit

class MinimumDiskSpaceCondition(
  private val application: Application,
  private val minimumDiskSpaceBytes: Long = 200_000_000,
  private val tryAgainDelayMillis: Long = TimeUnit.HOURS.toMillis(1)
) : HeapAnalysisCondition() {

  private val postedRetry = Runnable {
    trigger.conditionChanged(
        "$tryAgainDelayMillis ms passed since last memory check"
    )
  }

  override fun evaluate(): Result {
    uiHandler.removeCallbacks(postedRetry)
    val availableDiskSpace = availableDiskSpace()
    return if (availableDiskSpace >= minimumDiskSpaceBytes) {
      StartAnalysis
    } else {
      uiHandler.postDelayed(postedRetry, tryAgainDelayMillis)
      StopAnalysis(
          "availableDiskSpace $availableDiskSpace < minimumDiskSpaceBytes $minimumDiskSpaceBytes"
      )
    }
  }

  private fun availableDiskSpace(): Long {
    val filesDir = application.filesDir!!
    return StatFs(filesDir.absolutePath).run {
      if (SDK_INT >= 18) {
        availableBlocksLong * blockSizeLong
      } else {
        availableBlocks * blockSize.toLong()
      }
    }
  }
}