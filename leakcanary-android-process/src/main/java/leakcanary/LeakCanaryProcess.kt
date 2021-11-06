package leakcanary

import android.content.Context
import androidx.work.WorkInfo.State
import androidx.work.WorkManager

/**
 * Used to determine whether the current process is the LeakCanary analyzer process. By depending
 * on the `leakcanary-android-process` artifact instead of the `leakcanary-android`, LeakCanary
 * will automatically run its analysis in a separate process.
 *
 * As such, you'll need to be careful to do any custom configuration of LeakCanary in both the main
 * process and the analyzer process.
 */
object LeakCanaryProcess {

  @Volatile private var isInAnalyzerProcess: Boolean? = null

  /**
   * Whether the current process is the process running the heap analyzer, which is
   * a different process than the normal app process.
   */
  fun isInAnalyzerProcess(context: Context): Boolean {
    var isInAnalyzerProcess: Boolean? = isInAnalyzerProcess
    // This only needs to be computed once per process.
    if (isInAnalyzerProcess == null) {
      isInAnalyzerProcess = isInWorkerProcess(context)
      this.isInAnalyzerProcess = isInAnalyzerProcess
    }
    return isInAnalyzerProcess
  }

  private fun isInWorkerProcess(
    context: Context
  ): Boolean {
    var workerRunning = false
    val workManager = WorkManager.getInstance(context)
    val activeWorkers = workManager.getWorkInfosByTag("heap_analyzer_worker").get()

    if (activeWorkers.isEmpty()) {
      return false
    }

    for (worker in activeWorkers) {
      workerRunning = worker.state == State.RUNNING || worker.state == State.ENQUEUED
    }

    return workerRunning
  }
}
