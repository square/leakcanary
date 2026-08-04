package leakcanary

import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingWorkPolicy.KEEP
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.io.File
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalyzerWorker
import leakcanary.internal.HeapAnalyzerWorker.Companion.asWorkerInputData
import leakcanary.internal.InternalLeakCanary
import shark.SharkLog

/**
 * When receiving a [HeapDump] event, starts a WorkManager worker that performs heap analysis.
 */
object WorkManagerHeapAnalyzer : EventListener {

  internal val validWorkManagerInClasspath by lazy {
    try {
      Class.forName("androidx.work.WorkManager")
      // We need Data.Builder.putByteArray which was introduced in WorkManager 2.1.0.
      // https://github.com/square/leakcanary/issues/2310
      val dataBuilderClass = Class.forName("androidx.work.Data\$Builder")
      dataBuilderClass.declaredMethods.any { it.name == "putByteArray" }.apply {
        if (!this) {
          SharkLog.d { "Could not find androidx.work.Data\$Builder.putByteArray, WorkManager should be at least 2.1.0." }
        }
      }
    } catch (ignored: Throwable) {
      false
    }
  }

  // setExpedited() requires WorkManager 2.7.0+
  private val workManagerSupportsExpeditedRequests by lazy {
    try {
      Class.forName("androidx.work.OutOfQuotaPolicy")
      true
    } catch (ignored: Throwable) {
      false
    }
  }

  internal fun OneTimeWorkRequest.Builder.addExpeditedFlag() = apply {
    if (workManagerSupportsExpeditedRequests) {
      setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }
  }

  /**
   * The name the analysis of [heapDumpFile] is enqueued under, so that asking for it a 2nd time
   * doesn't queue a 2nd analysis of the same heap dump. LeakCanary asks again for any heap dump it
   * has no analysis for, which is the only way to tell that an analysis was dropped along with the
   * process it was queued in, and with [ExistingWorkPolicy.KEEP] that costs nothing when the analysis
   * WorkManager has is still coming.
   */
  internal fun heapAnalysisWorkName(heapDumpFile: File) = "heap-analysis-${heapDumpFile.name}"

  override fun onEvent(event: Event) {
    if (event is HeapDump) {
      val heapAnalysisRequest = OneTimeWorkRequest.Builder(HeapAnalyzerWorker::class.java).apply {
        setInputData(event.asWorkerInputData())
        addExpeditedFlag()
      }.build()
      SharkLog.d { "Enqueuing heap analysis for ${event.file} on WorkManager remote worker" }
      val application = InternalLeakCanary.application
      WorkManager.getInstance(application)
        .enqueueUniqueWork(heapAnalysisWorkName(event.file), KEEP, heapAnalysisRequest)
    }
  }
}
