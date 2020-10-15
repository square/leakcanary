package shark.internal

import shark.GcRoot
import shark.LibraryLeakReferenceMatcher
import shark.LeakTraceReference

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
    abstract val refFromParentType: LeakTraceReference.ReferenceType
    abstract val refFromParentName: String

    /**
     * If this node is an instance, then this is the id of the class that actually
     * declares the node.
     */
    abstract val owningClassId: Long

    class LibraryLeakChildNode(
      override val objectId: Long,
      override val parent: ReferencePathNode,
      override val refFromParentType: LeakTraceReference.ReferenceType,
      override val refFromParentName: String,
      override val matcher: LibraryLeakReferenceMatcher,
      override val owningClassId: Long = 0
    ) : ChildNode(), LibraryLeakNode

    class NormalNode(
      override val objectId: Long,
      override val parent: ReferencePathNode,
      override val refFromParentType: LeakTraceReference.ReferenceType,
      override val refFromParentName: String,
      override val owningClassId: Long = 0
    ) : ChildNode()
  }

}