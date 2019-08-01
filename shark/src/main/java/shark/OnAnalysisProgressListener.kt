package shark

/**
 * Reports progress from the [HeapAnalyzer] as they occur, as [Step] values.
 */
interface OnAnalysisProgressListener {

  // These steps are defined in the order in which they occur.
  enum class Step {
    PARSING_HEAP_DUMP,
    FINDING_LEAKING_INSTANCES,
    FINDING_PATHS_TO_LEAKING_INSTANCES,
    FINDING_DOMINATORS,
    COMPUTING_NATIVE_RETAINED_SIZE,
    COMPUTING_RETAINED_SIZE,
    BUILDING_LEAK_TRACES,
    REPORTING_HEAP_ANALYSIS
  }

  fun onAnalysisProgress(step: Step)

  companion object {

    /**
     * A no-op [OnAnalysisProgressListener]
     */
    val NO_OP = object : OnAnalysisProgressListener {
      override fun onAnalysisProgress(step: Step) {
      }
    }

    /**
     * Utility function to create a [OnAnalysisProgressListener] from the passed in [block] lambda
     * instead of using the anonymous `object : OnAnalysisProgressListener` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val listener = OnAnalysisProgressListener {
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (Step) -> Unit): OnAnalysisProgressListener =
      object : OnAnalysisProgressListener {
        override fun onAnalysisProgress(step: Step) {
          block(step)
        }
      }
  }
}