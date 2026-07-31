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
   * How much of the heap this node accounts for, i.e. the heap that would be freed if every
   * object reported as growing was released. An object that several growing nodes hold onto is
   * split evenly between them, so [retained] is not a lower bound of what fixing this one node
   * would free, but the [retained] of all the growing nodes add up to the size of the subgraph
   * they hold together. See `LeakShareCalculator`.
   *
   * [UNKNOWN_RETAINED] for nodes that aren't reported as growing, for every node of a
   * [FirstHeapTraversal], which has no growing nodes to compute this from, and for the traversals
   * that skipped computing retained sizes (see [HeapTraversalInput.heapDumpCount]).
   */
  var retained: Retained = UNKNOWN_RETAINED
    internal set

  /**
   * The part of [retained] made of objects that no other growing node reaches, i.e. a lower bound
   * of what fixing this one node would free. [retained] splits the objects a node shares with the
   * other growing nodes between them, so it's larger than this and it moves when the set of
   * reported nodes changes; this only counts what this node holds on its own. Growing nodes that
   * hold the same data show up as a large [retained] with a small [exclusiveRetained].
   *
   * [UNKNOWN_RETAINED] whenever [retained] is.
   */
  var exclusiveRetained: Retained = UNKNOWN_RETAINED
    internal set

  /**
   * How much [retained] increased since the previous traversal, [ZERO_RETAINED] if the previous
   * traversal didn't report this node as growing, [UNKNOWN_RETAINED] if [retained] is unknown.
   *
   * The first traversal has no growing nodes, so this is only non 0 from the third traversal on.
   *
   * [retained] is relative to the other growing nodes of its traversal: the objects a node shares
   * with them are split between them. Two consecutive traversals usually report the same growing
   * nodes, but when they don't, part of this increase is that split changing rather than the heap
   * growing. A node that shared everything it held with one other growing node which then stopped
   * growing sees its [retained] double.
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
    newNode.exclusiveRetained = exclusiveRetained
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
          result.append(
            "    Retained size: ${retained.heapSize} (+ ${retainedIncrease.heapSize}), " +
              "${exclusiveRetained.heapSize} not shared"
          )
          result.appendLine()
          result.append(
            "    Retained objects: ${retained.objectCount} (+ ${retainedIncrease.objectCount}), " +
              "${exclusiveRetained.objectCount} not shared"
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
