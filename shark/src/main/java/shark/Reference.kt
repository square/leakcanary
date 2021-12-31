package shark

class Reference(
  /**
   * The value of the reference, i.e. the object the reference is pointing to.
   */
  val valueObjectId: Long,

  /**
   * Non null if this reference matches a known library leak pattern, null otherwise.
   *
   * The shortest path finder will only go through matching references after it has exhausted
   * references that don't match, prioritizing finding an application leak over a known library
   * leak.
   */
  val matchedLibraryLeak: LibraryLeakReferenceMatcher?,

  val lazyDetailsResolver: LazyDetails.Resolver
) {
  class LazyDetails(
    val name: String,
    val locationClassObjectId: Long,
    val locationType: ReferenceLocationType,
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
