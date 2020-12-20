package leakcanary

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.os.StatFs
import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.HeapAnalysisJob.Result

class MinimumDiskSpaceInterceptor(
  private val application: Application,
  private val minimumDiskSpaceBytes: Long = 200_000_000,
  private val processInfo: ProcessInfo = ProcessInfo.Real
) : HeapAnalysisInterceptor {

  override fun intercept(chain: Chain): Result {
    val availableDiskSpace = processInfo.availableDiskSpaceBytes(application.filesDir!!)
    if (availableDiskSpace < minimumDiskSpaceBytes) {
      chain.job.cancel("availableDiskSpace $availableDiskSpace < minimumDiskSpaceBytes $minimumDiskSpaceBytes")
    }
    return chain.proceed()
  }
}