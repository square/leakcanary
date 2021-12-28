package leakcanary.internal

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import leakcanary.EventListener.Event

internal class HeapAnalyzerWorker(appContext: Context, workerParams: WorkerParameters) :
  Worker(appContext, workerParams) {
  override fun doWork(): Result {
    val doneEvent =
      AndroidDebugHeapAnalyzer.runAnalysisBlocking(inputData.asEvent()) { event ->
        InternalLeakCanary.sendEvent(event)
      }
    InternalLeakCanary.sendEvent(doneEvent)
    return Result.success()
  }

  companion object {
    private const val EVENT_BYTES = "EVENT_BYTES"

    fun Event.asWorkerInputData(dataBuilder: Data.Builder = Data.Builder()) = dataBuilder
      .putByteArray(EVENT_BYTES, toByteArray())
      .build()

    inline fun <reified T> Data.asEvent(): T = Serializables.fromByteArray<T>(getByteArray(EVENT_BYTES)!!)!!
  }
}
