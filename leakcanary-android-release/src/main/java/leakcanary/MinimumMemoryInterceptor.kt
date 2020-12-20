package leakcanary

import android.app.Application
import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.ProcessInfo.AvailableRam.BelowThreshold
import leakcanary.ProcessInfo.AvailableRam.LowRamDevice
import leakcanary.ProcessInfo.AvailableRam.Memory

class MinimumMemoryInterceptor(
  private val application: Application,
  private val minimumRequiredAvailableMemoryBytes: Long = 100_000_000,
  private val processInfo: ProcessInfo = ProcessInfo.Real
) : HeapAnalysisInterceptor {

  override fun intercept(chain: Chain): HeapAnalysisJob.Result {
    when (val memory = processInfo.availableRam(application)) {
      LowRamDevice -> {
        chain.job.cancel("low ram device")
      }
      BelowThreshold -> {
        chain.job.cancel("low memory")
      }
      is Memory -> {
        if (memory.bytes < minimumRequiredAvailableMemoryBytes) {
          chain.job.cancel(
            "not enough free memory: available ${memory.bytes} < min $minimumRequiredAvailableMemoryBytes"
          )
        }
      }
    }

    return chain.proceed()
  }
}