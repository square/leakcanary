package leakcanary

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import leakcanary.internal.friendly.checkMainThread
import shark.SharkLog

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

  /**
   * Initial delay to wait for analysisExecutor to start analysis
   *
   * If not specified, the initial delay is 500 ms
   */
  private val analysisExecutorDelay: Duration = DEFAULT_ANALYSIS_DELAY
) {

  private val currentJob = AtomicReference<HeapAnalysisJob?>()
  private val analysisHandler = Handler(Looper.getMainLooper())
  private var submitAnalysisToExecutor: Runnable? = null

  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(
      context: Context,
      intent: Intent
    ) {
      if (intent.shouldScheduleAnalysis()) {
        val job =
          analysisClient.newJob(JobContext(ScreenOffTrigger::class))
        if (currentJob.compareAndSet(null, job)) {
          schedule {
            val result = job.execute()
            analysisCallback(result)
          }
        }
      } else {
        cancelScheduledAnalysis()
        currentJob.getAndUpdate { job ->
          job?.cancel("screen on again")
          null
        }
      }
    }
  }

  fun start() {
    checkMainThread()
    val intentFilter = IntentFilter().apply {
      addAction(ACTION_SCREEN_ON)
      addAction(ACTION_SCREEN_OFF)
    }
    if (Build.VERSION.SDK_INT >= 33) {
      val flags = Context.RECEIVER_EXPORTED
      application.registerReceiver(screenReceiver, intentFilter, flags)
    } else {
      application.registerReceiver(screenReceiver, intentFilter)
    }
  }

  fun stop() {
    checkMainThread()
    application.unregisterReceiver(screenReceiver)
  }

  private fun Intent.shouldScheduleAnalysis(): Boolean {
    return this.action == ACTION_SCREEN_OFF && currentJob.get() == null
  }

  private fun schedule(action: Runnable) {
    submitAnalysisToExecutor = Runnable {
      analysisExecutor.execute(action)
    }.also { analysisHandler.postDelayed(it, analysisExecutorDelay.inWholeMilliseconds) }
  }

  private fun cancelScheduledAnalysis() {
    submitAnalysisToExecutor?.let { analysisHandler.removeCallbacks(it) }
  }

  companion object {
    private val DEFAULT_ANALYSIS_DELAY = 500.milliseconds
  }
}
