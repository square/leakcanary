package leakcanary

sealed class LeakNode {
  abstract val instance: Long

  class RootNode(
    override val instance: Long
  ) : LeakNode()

  class ChildNode(
    override val instance: Long,
    val exclusion: ExclusionDescription?,
    val parent: LeakNode,
    /**
     * The reference from the parent to this node
     */
    val leakReference: LeakReference?
  ) : LeakNode()
}