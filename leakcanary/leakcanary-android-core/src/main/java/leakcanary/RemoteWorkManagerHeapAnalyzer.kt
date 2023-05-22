package leakcanary

import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_CLASS_NAME
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_PACKAGE_NAME
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalyzerWorker.Companion.asWorkerInputData
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.RemoteHeapAnalyzerWorker
import shark.SharkLog

/**
 * When receiving a [HeapDump] event, starts a WorkManager worker that performs heap analysis in
 * a dedicated :leakcanary process
 */
object RemoteWorkManagerHeapAnalyzer : EventListener {

  private const val REMOTE_SERVICE_CLASS_NAME = "leakcanary.internal.RemoteLeakCanaryWorkerService"

  internal val remoteLeakCanaryServiceInClasspath by lazy {
    try {
      Class.forName(REMOTE_SERVICE_CLASS_NAME)
      true
    } catch (ignored: Throwable) {
      false
    }
  }

  override fun onEvent(event: Event) {
    if (event is HeapDump) {
      val application = InternalLeakCanary.application
      val heapAnalysisRequest =
        OneTimeWorkRequest.Builder(RemoteHeapAnalyzerWorker::class.java).apply {
          val dataBuilder = Data.Builder()
            .putString(ARGUMENT_PACKAGE_NAME, application.packageName)
            .putString(ARGUMENT_CLASS_NAME, REMOTE_SERVICE_CLASS_NAME)
          setInputData(event.asWorkerInputData(dataBuilder))
          with(WorkManagerHeapAnalyzer) {
            addExpeditedFlag()
          }
        }.build()
      SharkLog.d { "Enqueuing heap analysis for ${event.file} on WorkManager remote worker" }
      val workManager = WorkManager.getInstance(application)
      workManager.enqueue(heapAnalysisRequest)
    }
  }
}
