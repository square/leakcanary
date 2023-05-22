package leakcanary

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.HeapAnalysisJob.Result
import java.util.concurrent.TimeUnit

/**
 * Proceeds once per [period] (of time) and then cancels all follow up jobs until [period] has
 * passed.
 */
class OncePerPeriodInterceptor(
  application: Application,
  private val periodMillis: Long = TimeUnit.DAYS.toMillis(1)
) : HeapAnalysisInterceptor {

  private val preference: SharedPreferences by lazy {
    application.getSharedPreferences("OncePerPeriodInterceptor", Context.MODE_PRIVATE)!!
  }

  override fun intercept(chain: Chain): Result {
    val lastStartTimestamp = preference.getLong(LAST_START_TIMESTAMP_KEY, 0)
    val now = System.currentTimeMillis()
    val elapsedMillis = now - lastStartTimestamp

    if (elapsedMillis < periodMillis) {
      chain.job.cancel("not enough time elapsed since last analysis: elapsed $elapsedMillis ms < period $periodMillis ms")
    }

    return chain.proceed().apply {
      if (this is Result.Done) {
        preference.edit().putLong(LAST_START_TIMESTAMP_KEY, now).apply()
      }
    }
  }

  fun forget() {
    preference.edit().clear().apply()
  }

  companion object {
    private const val LAST_START_TIMESTAMP_KEY = "last_start_timestamp"
  }
}