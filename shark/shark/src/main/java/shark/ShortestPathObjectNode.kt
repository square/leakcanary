package shark

import shark.ByteSize.Companion.bytes

typealias GrowingObjectNodes = List<ShortestPathObjectNode>

class ShortestPathObjectNode(
  val name: String,
  val parent: ShortestPathObjectNode?,
  internal val newNode: Boolean
) {
  @Suppress("VariableNaming")
  internal val _children = mutableListOf<ShortestPathObjectNode>()
  val children: List<ShortestPathObjectNode>
    get() = _children

  var selfObjectCount = 0
    internal set
  var selfObjectCountIncrease = 0
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

  val childrenObjectCount: Int
    get() = _children.sumOf { it.selfObjectCount }

  val childrenObjectCountIncrease: Int
    get() = _children.sumOf { it.selfObjectCountIncrease }

  fun copyResettingAsInitialTree(): ShortestPathObjectNode {
    return copyResetRecursive(null)
  }

  private fun copyResetRecursive(newParent: ShortestPathObjectNode?): ShortestPathObjectNode {
    val newNode = ShortestPathObjectNode(name, newParent, true)
    newNode.selfObjectCount = selfObjectCount
    newNode.selfObjectCountIncrease = 0
    newNode.retainedOrNull = retainedOrNull
    if (retainedOrNull != null) {
      newNode.retainedIncreaseOrNull = Retained(0L.bytes, 0)
    }
    newNode.growing = true
    _children.forEach { child ->
      newNode._children += child.copyResetRecursive(newNode)
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
        val childrenByMostIncreasedFirst =
          pathNode.children
            // TODO Ideally here we'd filter on the increase threshold (e.g. 5 instead of 0)
            .filter { it.selfObjectCountIncrease > 0 }
            .sortedBy { -it.selfObjectCountIncrease }
        result.append(
          childrenByMostIncreasedFirst.joinToString(
            separator = "\n",
            postfix = "\n"
          ) { child ->
            "    ${child.selfObjectCount} objects (${child.selfObjectCountIncrease} new): ${child.name}"
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
