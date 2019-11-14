package shark

import shark.MetadataExtractor.Companion.invoke
import shark.ObjectInspector.Companion.invoke

/**
 * Extracts metadata from a hprof to be reported in [HeapAnalysisSuccess.metadata].
 *
 * You can create a [MetadataExtractor] from a lambda by calling [invoke].
 */
interface MetadataExtractor {
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