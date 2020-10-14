package leakcanary

import android.os.Build
import leakcanary.HeapAnalysisCondition.Result.StartAnalysis
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis

class GoodAndroidVersionCondition : HeapAnalysisCondition() {
  private val cachedResult: Result by lazy {
    val sdkInt = Build.VERSION.SDK_INT
    if (// findObjectById() sometimes failing. See #1759
        sdkInt != 23 &&
        // findObjectById() sometimes failing. See #1759
        sdkInt != 25 &&
        // Android 11 seem to sometimes have super slow heap dumps.
        // See https://issuetracker.google.com/issues/168634429
        sdkInt < 30
    ) {
      StartAnalysis
    } else {
      StopAnalysis("Build.VERSION.SDK_INT $sdkInt not supported")
    }
  }

  override fun evaluate() = cachedResult
}