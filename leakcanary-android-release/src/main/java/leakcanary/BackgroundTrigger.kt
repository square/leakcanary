package leakcanary

import android.app.Application
import android.os.Process
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import leakcanary.internal.BackgroundListener
import leakcanary.internal.checkMainThread
import shark.SharkLog
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class BackgroundTrigger(
  private val application: Application,
  private val analysisClient: HeapAnalysisClient,
  /**
   * The executor on which the analysis is performed and on which [analysisCallback] is called.
   * This should likely be a serial executor.
   * Defaults to a single thread executor with a background thread priority.
   */
  private val analysisExecutor: Executor = Executors.newSingleThreadExecutor {
    Thread {
      Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)
      it.run()
    }.apply {
      name = "BackgroundTrigger analysis executor"
    }
  },

  private val processInfo: ProcessInfo = ProcessInfo.Real,
  /**
   * Called back with a [HeapAnalysisJob.Result] after the app has entered background and a
   * heap analysis was attempted. This is called on the same thread that the analysis was
   * performed on.
   *
   * Defaults to logging to [SharkLog] (don't forget to set [SharkLog.logger] if you do want to see
   * logs).
   */
  private val analysisCallback: (HeapAnalysisJob.Result) -> Unit = { result ->
    SharkLog.d { "$result" }
  },
) {

  @Volatile
  private var currentJob: HeapAnalysisJob? = null

  private val backgroundListener = BackgroundListener(processInfo) { appInBackgroundNow ->
    if (appInBackgroundNow) {
      check(currentJob == null) {
        "Current job set to null when leaving background"
      }

      val job =
        analysisClient.newJob(JobContext(BackgroundTrigger::class))
      currentJob = job
      analysisExecutor.execute {
        val result = job.execute()
        currentJob = null
        analysisCallback(result)
      }
    } else {
      currentJob?.cancel("app left background")
      currentJob = null
    }
  }

  fun start() {
    checkMainThread()
    backgroundListener.install(application)
  }

  fun stop() {
    checkMainThread()
    backgroundListener.uninstall(application)
  }
}