package leakcanary

interface AnalyzerProgressListener {

  // These steps should be defined in the order in which they occur.
  enum class Step {
    READING_HEAP_DUMP_FILE,
    PARSING_HEAP_DUMP,
    SCANNING_HEAP_DUMP,
    FINDING_WATCHED_REFERENCES,
    DEDUPLICATING_GC_ROOTS,
    FINDING_LEAKING_REF,
    FINDING_LEAKING_REFS,
    FINDING_SHORTEST_PATH,
    FINDING_SHORTEST_PATHS,
    FINDING_DOMINATORS,
    COMPUTING_NATIVE_RETAINED_SIZE,
    COMPUTING_RETAINED_SIZE,
    BUILDING_LEAK_TRACE,
    BUILDING_LEAK_TRACES,
    COMPUTING_DOMINATORS
  }

  fun onProgressUpdate(step: Step)

  companion object {
    val NONE = object : AnalyzerProgressListener {
      override fun onProgressUpdate(step: Step) {
      }
    }
  }
}