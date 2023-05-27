package shark

// TODO Revisit this API. It's more like a GC Root + some priority / tagging.
class GcRootReference(
  val gcRoot: GcRoot,
  val isLowPriority: Boolean,
  val matchedLibraryLeak: LibraryLeakReferenceMatcher?,
)
