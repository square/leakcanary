package shark.internal

import shark.GcRoot
import shark.LibraryLeakReferenceMatcher
import shark.internal.Reference.LazyDetails

internal sealed class ReferencePathNode {
  abstract val objectId: Long

  sealed class RootNode : ReferencePathNode() {
    abstract val gcRoot: GcRoot
    override val objectId: Long
      get() = gcRoot.id

    class LibraryLeakRootNode(
      override val gcRoot: GcRoot,
      val matcher: LibraryLeakReferenceMatcher
    ) : RootNode()

    class NormalRootNode(
      override val gcRoot: GcRoot
    ) : RootNode()
  }

  class ChildNode(
    override val objectId: Long,
    /**
     * The reference from the parent to this node
     */
    val parent: ReferencePathNode,
    val lazyDetailsResolver: LazyDetails.Resolver,
  ) : ReferencePathNode()
}
