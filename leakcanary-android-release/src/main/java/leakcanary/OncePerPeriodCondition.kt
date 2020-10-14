package leakcanary

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import leakcanary.HeapAnalysisCondition.Result
import leakcanary.HeapAnalysisCondition.Result.StartAnalysis
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis
import leakcanary.internal.uiHandler
import java.util.concurrent.TimeUnit

private const val LAST_START_TIMESTAMP_KEY = "last_start_timestamp"

/**
 * Returns [Result.StartAnalysis] once per period and then returns [Result.StopAnalysis].
 * This condition should be last in the chain, and ensures that we don't repeatedly try dumping
 * the heap.
 */
class OncePerPeriodCondition(
  application: Application,
  private val periodMillis: Long = TimeUnit.DAYS.toMillis(1)
) : HeapAnalysisCondition() {

  private val preference: SharedPreferences by lazy {
    application.getSharedPreferences("OncePerPeriodCondition", Context.MODE_PRIVATE)!!
  }

  private val postedRetry =
    Runnable { trigger.conditionChanged("enough time elapsed since last analysis") }

  override fun evaluate(): Result {
    uiHandler.removeCallbacks(postedRetry)
    val lastStartTimestamp = preference.getLong(LAST_START_TIMESTAMP_KEY, 0)
    val now = System.currentTimeMillis()
    val elapsed = now - lastStartTimestamp
    return if (elapsed >= periodMillis) {
      preference.edit().putLong(LAST_START_TIMESTAMP_KEY, now).apply()
      StartAnalysis
    } else {
      val remainingMillis = periodMillis - elapsed
      uiHandler.postDelayed(postedRetry, remainingMillis)
      StopAnalysis(
          "Not enough time elapsed since last analysis: elapsed $elapsed < period $periodMillis"
      )
    }
  }
}