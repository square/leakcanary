package leakcanary

import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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

  internal val workManagerInClasspath by lazy {
    try {
      Class.forName("androidx.work.WorkManager")
      true
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

  override fun onEvent(event: Event) {
    if (event is HeapDump) {
      val heapAnalysisRequest = OneTimeWorkRequest.Builder(HeapAnalyzerWorker::class.java).apply {
        setInputData(event.asWorkerInputData())
        addExpeditedFlag()
      }.build()
      SharkLog.d { "Enqueuing heap analysis for ${event.file} on WorkManager remote worker" }
      val application = InternalLeakCanary.application
      WorkManager.getInstance(application).enqueue(heapAnalysisRequest)
    }
  }
}
