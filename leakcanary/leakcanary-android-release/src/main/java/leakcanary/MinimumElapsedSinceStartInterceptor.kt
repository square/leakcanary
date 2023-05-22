package leakcanary

import android.annotation.SuppressLint
import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.HeapAnalysisJob.Result
import java.util.concurrent.TimeUnit

@SuppressLint("NewApi")
class MinimumElapsedSinceStartInterceptor(
  private val minimumElapsedSinceStartMillis: Long = TimeUnit.SECONDS.toMillis(30),
  private val processInfo: ProcessInfo = ProcessInfo.Real
) : HeapAnalysisInterceptor {

  override fun intercept(chain: Chain): Result {
    if (processInfo.elapsedMillisSinceStart < minimumElapsedSinceStartMillis) {
      chain.job.cancel("app started less than $minimumElapsedSinceStartMillis ms ago.")
    }
    return chain.proceed()
  }
}