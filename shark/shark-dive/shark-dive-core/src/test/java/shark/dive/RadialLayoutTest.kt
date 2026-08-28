package shark.dive

import kotlin.math.cos
import kotlin.math.sin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.Test

class RadialLayoutTest {

  /** A tree of named nodes, where a parent's weight is the sum of its children's. */
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

  /** Wider than tall, so that the rings are bounded by the height and centred in the width. */
  private val viewport = TreemapRect(0.0, 0.0, 1000.0, 800.0)

  private val TreemapCell<Node>.node: Node get() = (subject as CellSubject.Node).node

  private val RadialLayoutResult<Node>.nodeCells: List<RadialCell<Node>>
    get() = cells.filter { it.subject is CellSubject.Node }

  private val RadialLayoutResult<Node>.groups: List<RadialCell<Node>>
    get() = cells.filter { it.subject is CellSubject.Group }

  private val RadialCell<Node>.node: Node get() = (subject as CellSubject.Node).node

  private val RadialLayoutResult<Node>.names: List<String>
    get() = nodeCells.map { it.node.name }

  @Test fun `the root is the disk in the middle`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = RadialLayout<Node>(ringCount = 4).layout(tree, viewport)

    val rootCell = result.cells.first()
    assertThat(rootCell.node.name).isEqualTo("root")
    assertThat(rootCell.depth).isEqualTo(0)
    assertThat(rootCell.arc.innerRadius).isEqualTo(0.0)
    assertThat(rootCell.arc.sweepAngle).isEqualTo(RadialArc.FULL_CIRCLE)
    // The shorter side of the viewport, over the centre disk plus its rings.
    assertThat(rootCell.arc.outerRadius).isEqualTo(400.0 / 5)
    assertThat(result.center).isEqualTo(TreemapPoint(500.0, 400.0))
  }

  @Test fun `children fill their parent's sweep in proportion to their weight`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 30), Node("b", 10))))

    val result = RadialLayout<Node>().layout(tree, viewport)

    val children = result.nodeCells.drop(1)
    assertThat(children.map { it.node.name }).containsExactly("a", "b")
    assertThat(children.map { it.arc.sweepAngle }).containsExactly(270.0, 90.0)
    // Starting at 12 o'clock, each one where the one before it ended.
    assertThat(children.map { it.arc.startAngle }).containsExactly(-90.0, 180.0)
    assertThat(children.map { it.arc.innerRadius }).containsOnly(result.cells.first().arc.outerRadius)
  }

  @Test fun `each level is one ring further out`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 2, leafWeight = 1_000_000))

    val result = RadialLayout<Node>(ringCount = 8).layout(tree, viewport)

    val ringWidth = 400.0 / 9
    result.cells.forEach { cell ->
      assertThat(cell.arc.outerRadius).isCloseTo((cell.depth + 1) * ringWidth, offset(1e-9))
      assertThat(cell.arc.innerRadius).isCloseTo(cell.depth * ringWidth, offset(1e-9))
    }
  }

  @Test fun `a parent is always laid out before its descendants`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 3, leafWeight = 1_000_000))

    val result = RadialLayout<Node>().layout(tree, viewport)

    val positions = result.nodeCells.withIndex().associate { (index, cell) -> cell.node.name to index }
    result.nodeCells.forEach { cell ->
      val parent = (cell.subject as CellSubject.Node).parent ?: return@forEach
      assertThat(positions.getValue(parent.name)).isLessThan(positions.getValue(cell.node.name))
    }
  }

  @Test fun `nothing is laid out past the last ring`() {
    val tree = NodeTree(uniformTree("root", depth = 6, breadth = 2, leafWeight = 1_000_000))

    val result = RadialLayout<Node>(ringCount = 3).layout(tree, viewport)

    assertThat(result.cells.map { it.depth }.max()).isEqualTo(3)
  }

  @Test fun `a sector too short to subdivide is left as it is`() {
    // One huge child and one fortieth of it: the small one still gets a sector of its own, but its
    // own children would be slivers, so it stays whole.
    val tree = NodeTree(
      Node(
        "root",
        children = listOf(
          uniformTree("big", depth = 2, breadth = 2, leafWeight = 1_000_000),
          Node("small", children = listOf(Node("small.0", 50_000), Node("small.1", 50_000)))
        )
      )
    )

    val result = RadialLayout<Node>().layout(tree, viewport)

    assertThat(result.names).contains("big.0", "small")
    assertThat(result.names).doesNotContain("small.0", "small.1")
  }

  @Test fun `children past the per node limit become one sector`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = RadialLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    assertThat(result.names).hasSize(11) // The root and its 10 largest children.
    val group = result.groups.single()
    assertThat((group.subject as CellSubject.Group).nodeCount).isEqualTo(40)
    assertThat(group.depth).isEqualTo(1)
    assertThat(group.weight).isEqualTo((51L..90L).sum())
  }

  @Test fun `a group and its siblings fill the whole sweep`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = RadialLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    val ring = result.cells.filter { it.depth == 1 }
    assertThat(ring.sumOf { it.arc.sweepAngle }).isCloseTo(RadialArc.FULL_CIRCLE, offset(1e-9))
    // Laid along the ring with no gaps, the group last.
    assertThat(ring.zipWithNext()).allSatisfy { (before, after) ->
      assertThat(after.arc.startAngle)
        .isCloseTo(before.arc.startAngle + before.arc.sweepAngle, offset(1e-9))
    }
    assertThat(ring.last().subject).isInstanceOf(CellSubject.Group::class.java)
  }

  @Test fun `cell count stays within the budget`() {
    val tree = NodeTree(uniformTree("root", depth = 8, breadth = 4, leafWeight = 1_000_000))

    val result = RadialLayout<Node>(maxCells = 200).layout(tree, viewport)

    assertThat(result.cells.size).isLessThanOrEqualTo(200)
  }

  @Test fun `reaching the budget is reported as truncation`() {
    val tree = NodeTree(uniformTree("root", depth = 4, breadth = 4, leafWeight = 1_000_000))

    val result = RadialLayout<Node>(maxCells = 10).layout(tree, viewport)

    assertThat(result.truncatedNodeCount).isGreaterThan(0)
  }

  @Test fun `a tree that fits is not reported as truncated`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = RadialLayout<Node>().layout(tree, viewport)

    assertThat(result.truncatedNodeCount).isEqualTo(0)
  }

  @Test fun `hit testing finds the sector a point falls in`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 30), Node("b", 10))))

    val result = RadialLayout<Node>().layout(tree, viewport)

    val a = result.nodeCells.single { it.node.name == "a" }
    assertThat(result.cellAt(result.middleOf(a))).isEqualTo(a)
    val b = result.nodeCells.single { it.node.name == "b" }
    assertThat(result.cellAt(result.middleOf(b))).isEqualTo(b)
  }

  @Test fun `hit testing the middle returns the root`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = RadialLayout<Node>().layout(tree, viewport)

    assertThat(result.cellAt(result.center)).isEqualTo(result.cells.first())
  }

  @Test fun `hit testing outside the rings returns null`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = RadialLayout<Node>(ringCount = 2).layout(tree, viewport)

    assertThat(result.cellAt(TreemapPoint(viewport.left, viewport.top))).isNull()
  }

  @Test fun `an empty viewport lays out only the root`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = RadialLayout<Node>().layout(tree, TreemapRect(0.0, 0.0, 0.0, 0.0))

    assertThat(result.cells).hasSize(1)
  }

  @Test fun `zero weight children are left out`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("empty", 0))))

    val result = RadialLayout<Node>().layout(tree, viewport)

    assertThat(result.names).containsExactly("root", "a")
  }

  @Test fun `layout is deterministic`() {
    val tree = NodeTree(uniformTree("root", depth = 4, breadth = 3, leafWeight = 1_000_000))
    val layout = RadialLayout<Node>()

    val first = layout.layout(tree, viewport)
    val second = layout.layout(tree, viewport)

    assertThat(second.cells).isEqualTo(first.cells)
  }

  @Test fun `laying out a subtree puts it in the middle`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 2, leafWeight = 1_000_000))
    val subtree = tree.root.children.first()

    val result = RadialLayout<Node>().layout(tree, viewport, root = subtree)

    assertThat(result.cells.first().node.name).isEqualTo(subtree.name)
    assertThat(result.cells.first().arc.sweepAngle).isEqualTo(RadialArc.FULL_CIRCLE)
  }

  /** A point in the middle of [cell], in the same coordinates hit testing takes. */
  private fun RadialLayoutResult<Node>.middleOf(cell: RadialCell<Node>): TreemapPoint {
    val arc = cell.arc
    val radius = (arc.innerRadius + arc.outerRadius) / 2
    val angle = Math.toRadians(arc.startAngle + arc.sweepAngle / 2)
    return TreemapPoint(x = center.x + radius * cos(angle), y = center.y + radius * sin(angle))
  }
}
