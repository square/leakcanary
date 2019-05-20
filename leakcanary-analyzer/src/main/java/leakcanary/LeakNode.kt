package leakcanary

sealed class LeakNode {
  abstract val instance: Long
  /** Used by the shortest path finder to create a segmented FIFO queue using a priority queue. */
  abstract val visitOrder: Int

  class RootNode(
    override val instance: Long,
    override val visitOrder: Int
  ) : LeakNode()

  class ChildNode(
    override val instance: Long,
    override val visitOrder: Int,
    val exclusion: ExclusionDescription?,
    val parent: LeakNode,
    /**
     * The reference from the parent to this node
     */
    val leakReference: LeakReference?
  ) : LeakNode()
}