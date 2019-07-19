package leakcanary

/**
 * Used to report progress by the [HeapAnalyzer].
 */
interface AnalyzerProgressListener {

  // These steps are defined in the order in which they occur.
  enum class Step {
    PARSING_HEAP_DUMP,
    FINDING_LEAKING_INSTANCES,
    FINDING_PATHS_TO_LEAKING_INSTANCES,
    FINDING_DOMINATORS,
    COMPUTING_NATIVE_RETAINED_SIZE,
    COMPUTING_RETAINED_SIZE,
    BUILDING_LEAK_TRACES,
  }

  fun onProgressUpdate(step: Step)

  companion object {
    val NONE = object : AnalyzerProgressListener {
      override fun onProgressUpdate(step: Step) {
      }
    }
  }
}