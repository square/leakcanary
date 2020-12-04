package leakcanary

import android.app.Application
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis
import leakcanary.HeapAnalysisCondition.Trigger
import leakcanary.internal.ReleaseHeapAnalyzer
import leakcanary.internal.startBackgroundHandlerThread
import leakcanary.internal.uiHandler
import shark.HeapAnalysis
import shark.SharkLog

/**
 * Performs automatic heap dump & analysis when conditions are met.
 */
class ConditionalHeapAnalyzer(
  application: Application,
  private val conditions: List<HeapAnalysisCondition> = defaultConditions(application),
  config: HeapAnalysisConfig = HeapAnalysisConfig(),
  private val listener: Listener
) {

  interface Listener {
    /**
     * Called on the main thread.
     */
    fun onConditionsUpdate(result: Result)
  }

  sealed class Result {
    class HeapAnalysisResult(val heapAnalysis: HeapAnalysis) : Result() {
      override fun toString(): String {
        return heapAnalysis.toString()
      }
    }

    data class ConditionsNotMet(val reason: String) : Result()
  }

  private val heapAnalyzer = ReleaseHeapAnalyzer(application, config) { analysis ->
    uiHandler.post {
      listener.onConditionsUpdate(Result.HeapAnalysisResult(analysis))
    }
    conditionTrigger.conditionChanged("analysis done")
  }

  @Volatile
  private var enabled = false

  private val conditionTrigger: Trigger = object : Trigger {
    override fun conditionChanged(reason: String) {
      if (!enabled) {
        return
      }
      SharkLog.d { "Rescheduling heap analysis condition check because $reason" }
      backgroundHandler.removeCallbacks(checkConditions)
      backgroundHandler.post(checkConditions)
    }
  }

  private val checkConditions = Runnable {
    if (!enabled) {
      return@Runnable
    }
    conditions.forEach { condition ->
      condition.lateInitTrigger = conditionTrigger
      val result = condition.evaluate()
      if (result is StopAnalysis) {
        heapAnalyzer.stop()
        uiHandler.post {
          listener.onConditionsUpdate(Result.ConditionsNotMet(result.reason))
        }
        return@Runnable
      }
      if (!enabled) {
        return@Runnable
      }
    }
    heapAnalyzer.start()
  }

  private val backgroundHandler by lazy {
    startBackgroundHandlerThread("heap-analysis-conditions-check")
  }

  fun start() {
    enabled = true
    backgroundHandler.removeCallbacks(checkConditions)
    backgroundHandler.post(checkConditions)
  }

  fun stop() {
    enabled = false
    backgroundHandler.removeCallbacks(checkConditions)
    heapAnalyzer.stop()
  }

  fun removeAllHeapDumpFiles() {
    heapAnalyzer.removeAllHeapDumpFiles()
  }

  companion object {
    fun defaultConditions(application: Application): List<HeapAnalysisCondition> {
      return listOf(
          GoodAndroidVersionCondition(),
          BackgroundCondition(application),
          MinimumDiskSpaceCondition(application),
          MinimumMemoryCondition(application),
          MinimumElapsedSinceStartCondition(),
          OncePerPeriodCondition(application)
      )
    }
  }

}