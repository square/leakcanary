package shark.internal

import shark.GcRoot
import shark.LeakReference
import shark.LibraryLeakReferenceMatcher

internal sealed class ReferencePathNode {
  abstract val objectId: Long

  interface LibraryLeakNode {
    val matcher: LibraryLeakReferenceMatcher
  }

  sealed class RootNode : ReferencePathNode() {
    abstract val gcRoot: GcRoot

    class LibraryLeakRootNode(
      override val objectId: Long,
      override val gcRoot: GcRoot,
      override val matcher: LibraryLeakReferenceMatcher
    ) : RootNode(), LibraryLeakNode

    class NormalRootNode(
      override val objectId: Long,
      override val gcRoot: GcRoot
    ) : RootNode()

  }

  sealed class ChildNode : ReferencePathNode() {

    abstract val parent: ReferencePathNode

    /**
     * The reference from the parent to this node
     */
    abstract val referenceFromParent: LeakReference

    class LibraryLeakChildNode(
      override val objectId: Long,
      override val parent: ReferencePathNode,
      override val referenceFromParent: LeakReference,
      override val matcher: LibraryLeakReferenceMatcher
    ) : ChildNode(), LibraryLeakNode

    class NormalNode(
      override val objectId: Long,
      override val parent: ReferencePathNode,
      override val referenceFromParent: LeakReference
    ) : ChildNode()
  }

}