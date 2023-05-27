package shark

/**
 * Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata].
 */
fun interface MetadataExtractor {
  fun extractMetadata(graph: HeapGraph): Map<String, String>

  companion object {

    /**
     * A no-op [MetadataExtractor]
     */
    val NO_OP = MetadataExtractor { emptyMap() }
  }
}
