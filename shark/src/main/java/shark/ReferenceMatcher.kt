package shark

/**
 * Used to pattern match known patterns of references in the heap, either to ignore them
 * ([IgnoredReferenceMatcher]) or to mark them as library leaks ([LibraryLeakReferenceMatcher]).
 */
sealed class ReferenceMatcher {

  /** The pattern that references will be matched against. */
  abstract val pattern: ReferencePattern

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
  /**
   * Whether the identified leak may exist in the provided [HeapGraph]. Defaults to true. If
   * the heap dump comes from a VM that runs a different version of the library that doesn't
   * have the leak, then this should return false.
   */
  val patternApplies: (HeapGraph) -> Boolean = { true }
) : ReferenceMatcher() {
  override fun toString() = "library leak: $pattern"
}

/**
 * [IgnoredReferenceMatcher] should be used to match references that cannot ever create leaks. The
 * shortest path finder will never go through matching references.
 */
class IgnoredReferenceMatcher(override val pattern: ReferencePattern) : ReferenceMatcher() {
  override fun toString() = "ignored ref: $pattern"
}
