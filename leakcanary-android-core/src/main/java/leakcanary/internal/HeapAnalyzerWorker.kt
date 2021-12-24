package leakcanary.internal

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import leakcanary.AndroidDebugHeapAnalyzer
import leakcanary.EventListener.Event.HeapDump

internal class HeapAnalyzerWorker(appContext: Context, workerParams: WorkerParameters) :
  Worker(appContext, workerParams) {
  override fun doWork(): Result {
    AndroidDebugHeapAnalyzer.runAnalysisBlocking(inputData.asHeapDumpEvent())
    return Result.success()
  }

  companion object {
    private const val FILE_PATH = "FILE_PATH"
    private const val DURATION_MILLIS = "DURATION_MILLIS"
    private const val REASON = "REASON"

    fun HeapDump.asWorkerInputData(dataBuilder: Data.Builder = Data.Builder()) = dataBuilder
      .putString(FILE_PATH, file.absolutePath)
      .putLong(DURATION_MILLIS, durationMillis)
      .putString(REASON, reason)
      .build()

    fun Data.asHeapDumpEvent() = HeapDump(
      file = File(getString(FILE_PATH)!!),
      durationMillis = getLong(DURATION_MILLIS, -1),
      reason = getString(REASON)!!
    )
  }
}
