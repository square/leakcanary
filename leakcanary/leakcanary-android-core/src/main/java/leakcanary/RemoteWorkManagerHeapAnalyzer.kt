package leakcanary

import androidx.work.Data
import androidx.work.ExistingWorkPolicy.KEEP
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_CLASS_NAME
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_PACKAGE_NAME
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalysisDoneDispatchWorker
import leakcanary.internal.HeapAnalyzerWorker.Companion.asWorkerInputData
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.RemoteHeapAnalyzerWorker
import shark.SharkLog

/**
 * When receiving a [HeapDump] event, starts a WorkManager worker that performs heap analysis in
 * a dedicated :leakcanary process, followed by a worker that runs in the main process and
 * dispatches the [EventListener.Event.HeapAnalysisDone] event to the listeners configured there.
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
      // The analysis runs in the :leakcanary process, so it can't dispatch the done event to the
      // listeners configured in the main process. It hands the analysis id to this second worker
      // instead, which WorkManager runs in the main process once the analysis succeeded.
      val dispatchDoneEventRequest =
        OneTimeWorkRequest.Builder(HeapAnalysisDoneDispatchWorker::class.java).apply {
          with(WorkManagerHeapAnalyzer) {
            addExpeditedFlag()
          }
        }.build()
      SharkLog.d { "Enqueuing heap analysis for ${event.file} on WorkManager remote worker" }
      WorkManager.getInstance(application)
        .beginUniqueWork(
          WorkManagerHeapAnalyzer.heapAnalysisWorkName(event.file), KEEP, heapAnalysisRequest
        )
        .then(dispatchDoneEventRequest)
        .enqueue()
    }
  }
}
