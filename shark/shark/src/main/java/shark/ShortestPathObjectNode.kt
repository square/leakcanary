package shark

typealias GrowingObjectNodes = List<ShortestPathObjectNode>

class ShortestPathObjectNode(
  val name: String,
  val parent: ShortestPathObjectNode?,
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
   */
  var retained: Retained = UNKNOWN_RETAINED
    internal set

  /**
   * Set for growing nodes if [retainedOrNull] is not null. Non 0 if the previous traversal also
   * computed retained size.
   */
  var retainedIncrease: Retained = UNKNOWN_RETAINED
    internal set

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
    val newNode = ShortestPathObjectNode(name, newParent)
    newNode.selfObjectCount = selfObjectCount
    newNode.retained = retained
    if (!retained.isUnknown) {
      newNode.retainedIncrease = ZERO_RETAINED
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
        if (!retained.isUnknown) {
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
}
