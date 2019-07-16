package leakcanary

import leakcanary.ReferenceMatcher.LibraryLeakReferenceMatcher

sealed class ReferencePathNode {
  abstract val instance: Long

  class RootNode(
    val gcRoot: GcRoot,
    override val instance: Long
  ) : ReferencePathNode()

  sealed class ChildNode : ReferencePathNode() {

    abstract val parent: ReferencePathNode

    /**
     * The reference from the parent to this node
     */
    abstract val referenceFromParent: LeakReference

    class LibraryLeakNode(
      override val instance: Long,
      override val parent: ReferencePathNode,
      override val referenceFromParent: LeakReference,
      val matcher: LibraryLeakReferenceMatcher
    ) : ChildNode()

    class NormalNode(
      override val instance: Long,
      override val parent: ReferencePathNode,
      override val referenceFromParent: LeakReference
    ) : ChildNode()
  }

}