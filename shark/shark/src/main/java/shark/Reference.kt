package shark

import shark.Reference.LazyDetails.Resolver

/**
 * TODO Review as public API.
 */
data class Reference(
  /**
   * The value of the reference, i.e. the object the reference is pointing to.
   */
  val valueObjectId: Long,

  /**
   * Low priority references are references that should be explored after any non low priority
   * reference has been explored. This ensures that such references are not on the shortest best
   * path if there is any other path that doesn't include any low priority reference.
   *
   * This is useful to highlight references that most likely exist due to known leaks (which means
   * we can potentially find unknown leaks instead) as well as references which are harder to
   * interpret for developers (such as java locals).
   */
  val isLowPriority: Boolean,

  /**
   * Whether this object should be treated as a leaf / sink object with no outgoing references
   * (regardless of its actual content).
   */
  val isLeafObject: Boolean = false,

  val lazyDetailsResolver: Resolver,
) {
  class LazyDetails(
    val name: String,
    val locationClassObjectId: Long,
    val locationType: ReferenceLocationType,
    /**
     * Non null if this reference matches a known library leak pattern, null otherwise.
     *
     * Usually associated  with [Reference.isLowPriority] being true, so that the shortest path
     * finder will only go through matching references after it has exhausted references that don't
     * match, prioritizing finding an application leak over a known library leak.
     */
    val matchedLibraryLeak: LibraryLeakReferenceMatcher?,
    // TODO Better name
    val isVirtual: Boolean
  ) {
    /**
     * Implementations should keep the minimal state they need and if needed rehydrate the objects
     * when resolving.
     */
    fun interface Resolver {
      fun resolve(): LazyDetails
    }
  }
}
