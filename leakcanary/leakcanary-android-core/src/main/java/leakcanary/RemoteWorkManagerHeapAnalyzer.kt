package leakcanary

import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_CLASS_NAME
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_PACKAGE_NAME
import java.io.File
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.HeapAnalyzerWorker.Companion.asWorkerInputData
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.RemoteHeapAnalyzerWorker
import leakcanary.internal.Serializables
import leakcanary.internal.friendly.mainHandler
import shark.SharkLog

/**
 * When receiving a [HeapDump] event, starts a WorkManager worker that performs heap analysis in
 * a dedicated :leakcanary process. When the analysis completes, the result is sent back to the
 * main process and dispatched to all configured event listeners.
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

  /**
   * Cleans up orphaned event files from previous remote worker runs.
   * If the main process died after a remote worker completed, the result file
   * would never be read or deleted. This dispatches any recoverable events
   * and deletes all leftover files.
   *
   * Called from [InternalLeakCanary.invoke] on startup.
   */
  internal fun dispatchAndCleanupOrphanedEventFiles() {
    val application = InternalLeakCanary.application
    val filesDir = application.filesDir
    val orphanedFiles = filesDir.listFiles { file ->
      file.name.startsWith(RemoteHeapAnalyzerWorker.EVENT_FILE_PREFIX)
    } ?: return
    for (eventFile in orphanedFiles) {
      try {
        val doneEvent = Serializables.fromByteArray<HeapAnalysisDone<*>>(eventFile.readBytes())
        if (doneEvent != null) {
          SharkLog.d { "Recovering orphaned remote event from ${eventFile.name}" }
          InternalLeakCanary.sendEvent(doneEvent)
        }
      } catch (e: Throwable) {
        SharkLog.d(e) { "Failed to recover orphaned event file ${eventFile.name}" }
      } finally {
        eventFile.delete()
      }
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

      // Observe the remote worker's completion from the main process so we can
      // re-dispatch the HeapAnalysisDone event to listeners running here.
      val workInfoLiveData = workManager.getWorkInfoByIdLiveData(heapAnalysisRequest.id)
      mainHandler.post {
        workInfoLiveData.observeForever(object : Observer<WorkInfo> {
          override fun onChanged(workInfo: WorkInfo) {
            if (workInfo.state.isFinished) {
              workInfoLiveData.removeObserver(this)
              if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                dispatchEventFromRemoteWorker(workInfo.outputData)
              }
            }
          }
        })
      }
    }
  }

  private fun dispatchEventFromRemoteWorker(outputData: Data) {
    val eventFilePath = outputData.getString(RemoteHeapAnalyzerWorker.EVENT_FILE_KEY)
    if (eventFilePath == null) {
      SharkLog.d { "Remote worker completed but no event file path in output data" }
      return
    }
    val eventFile = File(eventFilePath)
    if (!eventFile.exists()) {
      SharkLog.d { "Remote worker event file does not exist: $eventFilePath" }
      return
    }
    try {
      val doneEvent = Serializables.fromByteArray<HeapAnalysisDone<*>>(eventFile.readBytes())
      if (doneEvent != null) {
        InternalLeakCanary.sendEvent(doneEvent)
      } else {
        SharkLog.d { "Failed to deserialize remote worker event" }
      }
    } catch (e: Throwable) {
      SharkLog.d(e) { "Error reading remote worker event file" }
    } finally {
      eventFile.delete()
    }
  }
}
