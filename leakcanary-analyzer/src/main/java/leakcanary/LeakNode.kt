package leakcanary

sealed class LeakNode {
  abstract val instance: Long

  class RootNode(
    override val instance: Long
  ) : LeakNode()

  class ChildNode(
    override val instance: Long,
    val exclusion: Exclusion?,
    val parent: LeakNode,
    val leakReference: LeakReference?
  ) : LeakNode()
}