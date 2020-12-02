package shark

/**
 * Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata].
 *
 * This is a functional interface with which you can create a [MetadataExtractor] from a lambda.
 */
fun interface MetadataExtractor {
  fun extractMetadata(graph: HeapGraph): Map<String, String>

  companion object {

    /**
     * A no-op [MetadataExtractor]
     */
    val NO_OP = MetadataExtractor { emptyMap() }

    /**
     * Utility function to create a [MetadataExtractor] from the passed in [block] lambda instead of
     * using the anonymous `object : MetadataExtractor` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val inspector = MetadataExtractor { graph ->
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: (HeapGraph) -> Map<String, String>): MetadataExtractor =
      object : MetadataExtractor {
        override fun extractMetadata(graph: HeapGraph): Map<String, String> = block(graph)
      }
  }
}