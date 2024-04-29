package shark

import shark.ReferenceMatcher.Companion.ALWAYS

/**
 * Used to pattern match known patterns of references in the heap, either to ignore them
 * ([IgnoredReferenceMatcher]) or to mark them as library leaks ([LibraryLeakReferenceMatcher]),
 * which lowers their traversal priority when exploring the heap.
 */
sealed class ReferenceMatcher {

  /** The pattern that references will be matched against. */
  abstract val pattern: ReferencePattern

  /**
   * Whether the identified leak may exist in the provided [HeapGraph]. Defaults to true. If
   * the heap dump comes from a VM that runs a different version of the library that doesn't
   * have the leak, then this should return false.
   */
  abstract val patternApplies: (HeapGraph) -> Boolean

  fun interface ListBuilder {
    fun add(references: MutableList<ReferenceMatcher>)
  }

  companion object {
    val ALWAYS: (HeapGraph) -> Boolean = { true }

    /**
     * Builds a list of [ReferenceMatcher] from [referenceMatcherListBuilders].
     */
    fun fromListBuilders(referenceMatcherListBuilders: Collection<ListBuilder>): List<ReferenceMatcher> {
      val resultSet = mutableListOf<ReferenceMatcher>()
      referenceMatcherListBuilders.forEach {
        it.add(resultSet)
      }
      return resultSet
    }
  }
}

/**
 * [LibraryLeakReferenceMatcher] should be used to match references in library code that are
 * known to create leaks and are beyond your control. The shortest path finder will only go
 * through matching references after it has exhausted references that don't match, prioritizing
 * finding an application leak over a known library leak. Library leaks will be reported as
 * [LibraryLeak] instead of [ApplicationLeak].
 */
data class LibraryLeakReferenceMatcher(
  override val pattern: ReferencePattern,
  /**
   * A description that conveys what we know about this library leak.
   */
  val description: String = "",

  override val patternApplies: (HeapGraph) -> Boolean = ALWAYS
) : ReferenceMatcher() {
  override fun toString() = "library leak: $pattern"
}

/**
 * [IgnoredReferenceMatcher] should be used to match references that cannot ever create leaks. The
 * shortest path finder will never go through matching references.
 */
class IgnoredReferenceMatcher(
  override val pattern: ReferencePattern,
  override val patternApplies: (HeapGraph) -> Boolean = ALWAYS
) : ReferenceMatcher() {
  override fun toString() = "ignored ref: $pattern"
}

fun ReferencePattern.leak(
  description: String = "",
  patternApplies: (HeapGraph) -> Boolean = ALWAYS
) = LibraryLeakReferenceMatcher(this, description, patternApplies)

fun ReferencePattern.ignored(
  patternApplies: (HeapGraph) -> Boolean = ALWAYS
) = IgnoredReferenceMatcher(this, patternApplies)

internal fun Iterable<ReferenceMatcher>.filterFor(graph: HeapGraph): List<ReferenceMatcher> {
  return filter { matcher ->
    matcher.patternApplies(graph)
  }
}
