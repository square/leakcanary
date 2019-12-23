package shark.internal

import shark.GcRoot
import shark.LibraryLeakReferenceMatcher
import shark.ReferencePathElement

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
    abstract val refFromParentType: ReferencePathElement.ReferenceType
    abstract val refFromParentName: String

    class LibraryLeakChildNode(
      override val objectId: Long,
      override val parent: ReferencePathNode,
      override val refFromParentType: ReferencePathElement.ReferenceType,
      override val refFromParentName: String,
      override val matcher: LibraryLeakReferenceMatcher
    ) : ChildNode(), LibraryLeakNode

    class NormalNode(
      override val objectId: Long,
      override val parent: ReferencePathNode,
      override val refFromParentType: ReferencePathElement.ReferenceType,
      override val refFromParentName: String
    ) : ChildNode()
  }

}