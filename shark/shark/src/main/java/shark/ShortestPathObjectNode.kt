package shark

typealias GrowingObjectNodes = List<ShortestPathObjectNode>

class ShortestPathObjectNode(
  val nodeAndEdgeName: String,
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

  internal var growing = false

  val childrenObjectCount: Int
    get() = _children.sumOf { it.selfObjectCount }

  val childrenObjectCountIncrease: Int
    get() = _children.sumOf { it.selfObjectCountIncrease }

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
      result.append(pathNode.nodeAndEdgeName)
      result.append(" (")
      result.append(pathNode.selfObjectCount)
      result.append(" objects)")
      if (index == pathAfterRoot.lastIndex) {
        result.appendLine()
        result.append("    Children:")
        result.appendLine()
        val childrenByMostIncreasedFirst =
          pathNode.children.sortedBy { -it.selfObjectCountIncrease }
        result.append(
          childrenByMostIncreasedFirst.joinToString(
            separator = "\n",
            postfix = "\n"
          ) { child ->
            "    ${child.selfObjectCount} objects (${child.selfObjectCountIncrease} new): ${child.nodeAndEdgeName}"
          })
      } else {
        result.appendLine()
        result.append("│ ").appendLine()
      }
    }
    return result.toString()
  }
}
