package shark

import shark.ByteSize.Companion.bytes

typealias GrowingObjectNodes = List<ShortestPathObjectNode>

class ShortestPathObjectNode(
  val name: String,
  val parent: ShortestPathObjectNode?,
  internal val newNode: Boolean
) {
  // Null at first, then created with capacity set to the number of edges enqueued from that node.
  // This means we'll sometimes use a little more space than what we actually need, but the
  // trade-off is that we only get to create the array once, and there's no array size doubling.
  private var _children: MutableList<ShortestPathObjectNode>? = null

  // Null on initial run (all children are growing). After the first run, set with only
  // children that are constantly growing over the per children threshold.
  internal var growingChildrenArray: Array<ShortestPathObjectNode>? = null
  internal var growingChildrenIncreasesArray: IntArray? = null

  val children: List<ShortestPathObjectNode>
    get() = _children ?: emptyList()

  data class GrowingChildNode(
    val child: ShortestPathObjectNode,
    val objectCountIncrease: Int
  )

  /**
   * Returns a list of pair of child [ShortestPathObjectNode] and associated object count
   * increase, filtered to only the children nodes that were marked as growing, i.e. children
   * that had an object count increase greater or equal to the scenario loop count.
   */
  val growingChildren: List<GrowingChildNode>
    get() = growingChildrenArray!!.withIndex()
      .map { indexedValue ->
        GrowingChildNode(indexedValue.value, growingChildrenIncreasesArray!![indexedValue.index])
      }

  var selfObjectCount = 0
    internal set

  /**
   * Set for growing nodes if the traversal requested the computation of retained sizes, otherwise
   * null.
   * This is on the last 2 traversals.
   */
  var retainedOrNull: Retained? = null
    internal set

  /**
   * Set for growing nodes if [retainedOrNull] is not null. Non 0 if the previous traversal also
   * computed retained size.
   * This is on the last 2 traversals.
   */
  var retainedIncreaseOrNull: Retained? = null
    internal set

  val retained: Retained get() = retainedOrNull!!

  val retainedIncrease: Retained get() = retainedIncreaseOrNull!!

  internal var growing = false

  internal fun createChildrenBackingList(maxChildren: Int) {
    check(_children == null) {
      "Expected createChildList() to be called at most once per node."
    }
    _children = ArrayList(maxChildren)
  }

  internal fun addChild(child: ShortestPathObjectNode) {
    val children = checkNotNull(_children) {
      "Excepted createChildList() to have been called"
    }
    children.add(child)
  }

  fun copyResettingAsInitialTree(): ShortestPathObjectNode {
    return copyResetRecursive(null)
  }

  private fun copyResetRecursive(newParent: ShortestPathObjectNode?): ShortestPathObjectNode {
    val newNode = ShortestPathObjectNode(name, newParent, true)
    newNode.selfObjectCount = selfObjectCount
    newNode.retainedOrNull = retainedOrNull
    if (retainedOrNull != null) {
      newNode.retainedIncreaseOrNull = Retained(0L.bytes, 0)
    }
    newNode.growing = true
    newNode.createChildrenBackingList(children.size)
    val newChildren = newNode._children!!
    children.forEach { child ->
      newChildren += child.copyResetRecursive(newNode)
    }
    return newNode
  }

  override fun toString() = pathFromRootAsString()

  fun pathFromRootAsString(): String {
    val pathFromRoot = mutableListOf<ShortestPathObjectNode>()
    var unwindingNode: ShortestPathObjectNode? = this
    while (unwindingNode != null) {
      pathFromRoot.add(0, unwindingNode)
      unwindingNode = unwindingNode.parent
    }
    val pathAfterRoot = pathFromRoot.drop(1)
    val result = StringBuilder()
    result.append("\n┬───").appendLine()
    pathAfterRoot.forEachIndexed { index, pathNode ->
      if (index == 0) {
        result.append("│ ")
      } else if (index < pathAfterRoot.lastIndex) {
        result.append("├─")
      } else {
        result.append("╰→")
      }
      result.append(pathNode.name)
      result.append(" (")
      result.append(pathNode.selfObjectCount)
      result.append(" objects)")
      if (index == pathAfterRoot.lastIndex) {
        if (retainedOrNull != null) {
          result.appendLine()
          result.append("    Retained size: ${retained.heapSize} (+ ${retainedIncrease.heapSize})")
          result.appendLine()
          result.append(
            "    Retained objects: ${retained.objectCount} (+ ${retainedIncrease.objectCount})"
          )
        }
        result.appendLine()
        result.append("    Children:")
        result.appendLine()

        val childrenByMostIncreasedFirst = growingChildren
          .sortedBy { -it.objectCountIncrease }

        result.append(
          childrenByMostIncreasedFirst.joinToString(
            separator = "\n",
            postfix = "\n"
          ) { (child, increase) ->
            "    ${child.selfObjectCount} objects (${increase} new): ${child.name}"
          })
      } else {
        result.appendLine()
        result.append("│ ").appendLine()
      }
    }
    return result.toString()
  }

  class Retained(
    /**
     * The minimum number of bytes which would be freed if all references to this object were
     * released.
     */
    val heapSize: ByteSize,

    /**
     * The minimum number of objects which would be unreachable if all references to this object were
     * released.
     */
    val objectCount: Int,
  )
}
