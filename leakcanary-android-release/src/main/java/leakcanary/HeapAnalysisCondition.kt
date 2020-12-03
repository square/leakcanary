package leakcanary

import leakcanary.HeapAnalysisCondition.Result.StartAnalysis
import leakcanary.HeapAnalysisCondition.Result.StopAnalysis

/**
 * see [evaluate].
 */
abstract class HeapAnalysisCondition {

  /**
   * Evaluates whether the heap analysis should proceeds ([StartAnalysis]) or
   * stop ([StopAnalysis]).
   *
   * This is called from a single background thread.
   *
   * The heap analysis will be performed on yet another background thread.
   */
  abstract fun evaluate(): Result

  /**
   * Enables implementations of [HeapAnalysisCondition] to notify the engine that conditions
   * have changed and they should be reevaluated.
   */
  interface Trigger {
    fun conditionChanged(reason: String)
  }

  internal lateinit var lateInitTrigger: Trigger

  val trigger: Trigger
    get() = lateInitTrigger

  sealed class Result {
    object StartAnalysis : Result()
    class StopAnalysis(val reason: String) : Result()
  }
}