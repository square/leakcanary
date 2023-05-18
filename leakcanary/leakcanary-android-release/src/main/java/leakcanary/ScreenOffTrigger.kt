package leakcanary

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import leakcanary.internal.friendly.checkMainThread
import shark.SharkLog
import java.util.concurrent.Executor

class ScreenOffTrigger(
  private val application: Application,
  private val analysisClient: HeapAnalysisClient,
  /**
   * The executor on which the analysis is performed and on which [analysisCallback] is called.
   * This should likely be a single thread executor with a background thread priority.
   */
  private val analysisExecutor: Executor,

  /**
   * Called back with a [HeapAnalysisJob.Result] after the screen went off and a
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

  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(
      context: Context,
      intent: Intent
    ) {
      if (intent.action == ACTION_SCREEN_OFF) {
        if (currentJob == null) {
          val job =
            analysisClient.newJob(JobContext(ScreenOffTrigger::class))
          currentJob = job
          analysisExecutor.execute {
            val result = job.execute()
            currentJob = null
            analysisCallback(result)
          }
        }
      } else {
        currentJob?.cancel("screen on again")
        currentJob = null
      }
    }
  }

  fun start() {
    checkMainThread()
    application.registerReceiver(screenReceiver, IntentFilter().apply {
      addAction(ACTION_SCREEN_ON)
      addAction(ACTION_SCREEN_OFF)
    })
  }

  fun stop() {
    checkMainThread()
    application.unregisterReceiver(screenReceiver)
  }
}