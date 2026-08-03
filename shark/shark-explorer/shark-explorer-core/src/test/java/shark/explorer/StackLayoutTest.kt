package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.Test

class StackLayoutTest {

  /** A tree of named nodes, where a parent weighs what it holds itself plus what its children weigh. */
  private class Node(
    val name: String,
    val ownWeight: Long = 0,
    val children: List<Node> = emptyList()
  ) {
    val weight: Long = ownWeight + children.sumOf { it.weight }
  }

  private class NodeTree(override val root: Node) : TreemapTree<Node> {
    override fun weight(node: Node) = node.weight
    override fun children(node: Node) = node.children
  }

  /** A node with [breadth] children at every level, [depth] levels deep. */
  private fun uniformTree(
    name: String,
    depth: Int,
    breadth: Int,
    leafWeight: Long
  ): Node = if (depth == 0) {
    Node(name, ownWeight = leafWeight)
  } else {
    Node(
      name,
      children = (0 until breadth).map { uniformTree("$name.$it", depth - 1, breadth, leafWeight) }
    )
  }

  /** A chain of single children, which is what only a row bound stops: every row of it is full width. */
  private fun chain(length: Int): Node =
    (length downTo 1).fold(Node("chain.$length", ownWeight = 1_000_000)) { below, index ->
      Node("chain.${index - 1}", children = listOf(below))
    }

  private val viewport = TreemapRect(0.0, 0.0, 1000.0, 800.0)

  private val StackCell<Node>.node: Node get() = (subject as CellSubject.Node).node

  private val StackLayoutResult<Node>.nodeCells: List<StackCell<Node>>
    get() = cells.filter { it.subject is CellSubject.Node }

  private val StackLayoutResult<Node>.groups: List<StackCell<Node>>
    get() = cells.filter { it.subject is CellSubject.Group }

  private val StackLayoutResult<Node>.names: List<String>
    get() = nodeCells.map { it.node.name }

  private fun StackLayoutResult<Node>.cellOf(name: String): StackCell<Node> =
    nodeCells.single { it.node.name == name }

  @Test fun `the root is the row across the top`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = StackLayout<Node>(rowHeight = 20.0).layout(tree, viewport)

    val rootCell = result.cells.first()
    assertThat(rootCell.node.name).isEqualTo("root")
    assertThat(rootCell.depth).isEqualTo(0)
    assertThat(rootCell.rect).isEqualTo(TreemapRect(0.0, 0.0, 1000.0, 20.0))
  }

  @Test fun `children fill their parent's row in proportion to their weight`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 30), Node("b", 10))))

    val result = StackLayout<Node>(rowHeight = 20.0).layout(tree, viewport)

    val children = result.nodeCells.drop(1)
    assertThat(children.map { it.node.name }).containsExactly("a", "b")
    // Heaviest first from the left edge, each one where the one before it ended.
    assertThat(children.map { it.rect.left }).containsExactly(0.0, 750.0)
    assertThat(children.map { it.rect.right }).containsExactly(750.0, 1000.0)
    assertThat(children.map { it.rect.top }).containsOnly(20.0)
    assertThat(children.map { it.rect.bottom }).containsOnly(40.0)
  }

  @Test fun `a block is its share of the whole however deep it sits`() {
    // The one guarantee that makes rows comparable across levels: "a" holds as much itself as its child
    // does, so a child that filled its parent's row would read as twice what it is.
    val tree = NodeTree(
      Node(
        "root",
        children = listOf(
          Node("a", ownWeight = 50, children = listOf(Node("a.0", 50))),
          Node("b", ownWeight = 100)
        )
      )
    )

    val result = StackLayout<Node>().layout(tree, viewport)

    val rootWeight = tree.root.weight
    result.cells.forEach { cell ->
      assertThat(cell.rect.width)
        .isCloseTo(viewport.width * cell.weight / rootWeight, offset(1e-9))
    }
    assertThat(result.cellOf("a.0").rect.width).isEqualTo(250.0)
  }

  @Test fun `what a node holds itself is the block at the end of its row`() {
    val tree = NodeTree(Node("root", ownWeight = 250, children = listOf(Node("a", 750))))

    val result = StackLayout<Node>().layout(tree, viewport)

    val own = result.cells.single { it.subject is CellSubject.Own }
    assertThat((own.subject as CellSubject.Own).node.name).isEqualTo("root")
    assertThat(own.weight).isEqualTo(250)
    assertThat(own.depth).isEqualTo(1)
    // At the right end of the row, after the children, and up against its edge.
    assertThat(own.rect.left).isEqualTo(750.0)
    assertThat(own.rect.right).isEqualTo(viewport.right)
  }

  @Test fun `each level is one row further down`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 2, leafWeight = 1_000_000))

    val result = StackLayout<Node>(rowHeight = 18.0).layout(tree, viewport)

    result.cells.forEach { cell ->
      assertThat(cell.rect.top).isCloseTo(cell.depth * 18.0, offset(1e-9))
      assertThat(cell.rect.bottom).isCloseTo((cell.depth + 1) * 18.0, offset(1e-9))
    }
    assertThat(result.rowCount).isEqualTo(4)
    assertThat(result.contentHeight).isEqualTo(4 * 18.0)
  }

  @Test fun `a parent is always laid out before its descendants`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 3, leafWeight = 1_000_000))

    val result = StackLayout<Node>().layout(tree, viewport)

    val positions = result.nodeCells.withIndex().associate { (index, cell) -> cell.node.name to index }
    result.nodeCells.forEach { cell ->
      val parent = (cell.subject as CellSubject.Node).parent ?: return@forEach
      assertThat(positions.getValue(parent.name)).isLessThan(positions.getValue(cell.node.name))
    }
  }

  @Test fun `a block too narrow to subdivide is left as it is`() {
    // One huge child and a two hundred and fiftieth of it: the small one still gets a block of its own,
    // four pixels of the thousand, but that is under the width a row is worth dividing, so it stays whole.
    val tree = NodeTree(
      Node(
        "root",
        children = listOf(
          uniformTree("big", depth = 2, breadth = 2, leafWeight = 1_000_000),
          Node("small", children = listOf(Node("small.0", 8_000), Node("small.1", 8_000)))
        )
      )
    )

    val result = StackLayout<Node>().layout(tree, viewport)

    assertThat(result.names).contains("big.0", "small")
    assertThat(result.names).doesNotContain("small.0", "small.1")
  }

  @Test fun `nothing is laid out past the last row`() {
    val tree = NodeTree(chain(length = 20))

    val result = StackLayout<Node>(maxRows = 5).layout(tree, viewport)

    assertThat(result.cells.map { it.depth }.max()).isEqualTo(4)
    assertThat(result.rowCount).isEqualTo(5)
  }

  @Test fun `a chain of single children is as wide at the bottom as at the top`() {
    val tree = NodeTree(chain(length = 30))

    val result = StackLayout<Node>(maxRows = 32).layout(tree, viewport)

    assertThat(result.rowCount).isEqualTo(31)
    assertThat(result.cells.map { it.rect.width }).containsOnly(viewport.width)
  }

  @Test fun `children past the per node limit become one block`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = StackLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    assertThat(result.names).hasSize(11) // The root and its 10 largest children.
    val group = result.groups.single()
    assertThat((group.subject as CellSubject.Group).nodeCount).isEqualTo(40)
    assertThat(group.depth).isEqualTo(1)
    assertThat(group.weight).isEqualTo((51L..90L).sum())
  }

  @Test fun `a group and its siblings fill the whole row`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = StackLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    val row = result.cells.filter { it.depth == 1 }
    assertThat(row.first().rect.left).isEqualTo(viewport.left)
    assertThat(row.last().rect.right).isCloseTo(viewport.right, offset(1e-9))
    // Laid along the row with no gaps, the group last: it stands for what the row ran out of width for.
    assertThat(row.zipWithNext()).allSatisfy { (before, after) ->
      assertThat(after.rect.left).isCloseTo(before.rect.right, offset(1e-9))
    }
    assertThat(row.last().subject).isInstanceOf(CellSubject.Group::class.java)
  }

  @Test fun `cell count stays within the budget`() {
    val tree = NodeTree(uniformTree("root", depth = 8, breadth = 4, leafWeight = 1_000_000))

    val result = StackLayout<Node>(maxCells = 200).layout(tree, viewport)

    assertThat(result.cells.size).isLessThanOrEqualTo(200)
  }

  @Test fun `reaching the budget is reported as truncation`() {
    val tree = NodeTree(uniformTree("root", depth = 4, breadth = 4, leafWeight = 1_000_000))

    val result = StackLayout<Node>(maxCells = 10).layout(tree, viewport)

    assertThat(result.truncatedNodeCount).isGreaterThan(0)
  }

  @Test fun `a tree that fits is not reported as truncated`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = StackLayout<Node>().layout(tree, viewport)

    assertThat(result.truncatedNodeCount).isEqualTo(0)
  }

  @Test fun `hit testing finds the block a point falls in`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 30), Node("b", 10))))

    val result = StackLayout<Node>(rowHeight = 20.0).layout(tree, viewport)

    assertThat(result.cellAt(TreemapPoint(500.0, 10.0))).isEqualTo(result.cellOf("root"))
    assertThat(result.cellAt(TreemapPoint(500.0, 30.0))).isEqualTo(result.cellOf("a"))
    assertThat(result.cellAt(TreemapPoint(800.0, 30.0))).isEqualTo(result.cellOf("b"))
  }

  @Test fun `hit testing past the last row returns null`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = StackLayout<Node>(rowHeight = 20.0).layout(tree, viewport)

    // Two rows of twenty pixels, and a stack is only as tall as what it drew: the rest of a viewport
    // taller than that is no block of it.
    assertThat(result.contentHeight).isEqualTo(40.0)
    assertThat(result.cellAt(TreemapPoint(500.0, 40.0))).isNull()
  }

  @Test fun `an empty viewport lays out only the root`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = StackLayout<Node>().layout(tree, TreemapRect(0.0, 0.0, 0.0, 0.0))

    assertThat(result.cells).hasSize(1)
    assertThat(result.rowCount).isEqualTo(1)
  }

  @Test fun `zero weight children are left out`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("empty", 0))))

    val result = StackLayout<Node>().layout(tree, viewport)

    assertThat(result.names).containsExactly("root", "a")
  }

  @Test fun `layout is deterministic`() {
    val tree = NodeTree(uniformTree("root", depth = 4, breadth = 3, leafWeight = 1_000_000))
    val layout = StackLayout<Node>()

    val first = layout.layout(tree, viewport)
    val second = layout.layout(tree, viewport)

    assertThat(second.cells).isEqualTo(first.cells)
  }

  @Test fun `laying out a subtree puts it across the top`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 2, leafWeight = 1_000_000))
    val subtree = tree.root.children.first()

    val result = StackLayout<Node>().layout(tree, viewport, root = subtree)

    val rootCell = result.cells.first()
    assertThat(rootCell.node.name).isEqualTo(subtree.name)
    assertThat(rootCell.rect.width).isEqualTo(viewport.width)
    assertThat(rootCell.depth).isEqualTo(0)
  }
}
