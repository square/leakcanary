package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.assertj.core.data.Percentage.withPercentage
import org.junit.Test

class TreemapLayoutTest {

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

  private val viewport = TreemapRect(0.0, 0.0, 1000.0, 800.0)

  private val TreemapLayoutResult<Node>.nodeCells: List<TreemapCell<Node>>
    get() = cells.filter { it.subject is CellSubject.Node }

  private val TreemapLayoutResult<Node>.names: List<String>
    get() = nodeCells.map { it.node.name }

  private val TreemapLayoutResult<Node>.groups: List<TreemapCell<Node>>
    get() = cells.filter { it.subject is CellSubject.Group }

  // What a cell stands for, for the cells a test already knows the kind of.
  private val TreemapCell<Node>.node: Node get() = (subject as CellSubject.Node).node
  private val TreemapCell<Node>.parent: Node? get() = (subject as CellSubject.Node).parent
  private val TreemapCell<Node>.siblingIndex: Int
    get() = (subject as CellSubject.Node).siblingIndex
  private val TreemapCell<Node>.groupParent: Node get() = (subject as CellSubject.Group).parent
  private val TreemapCell<Node>.nodeCount: Int get() = (subject as CellSubject.Group).nodeCount

  @Test fun `root is laid out into the whole viewport`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    val rootCell = result.nodeCells.first()
    assertThat(rootCell.node.name).isEqualTo("root")
    assertThat(rootCell.depth).isEqualTo(0)
    assertThat(rootCell.rect).isEqualTo(viewport)
    assertThat(rootCell.weight).isEqualTo(15)
  }

  @Test fun `a parent is always laid out before its descendants`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 3, leafWeight = 100))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    val positions = result.nodeCells.withIndex()
      .associate { (index, cell) -> cell.node.name to index }
    result.nodeCells.forEach { cell ->
      cell.node.children.forEach { child ->
        val childPosition = positions[child.name]
        if (childPosition != null) {
          assertThat(childPosition)
            .describedAs("${child.name} must come after its parent ${cell.node.name}")
            .isGreaterThan(positions.getValue(cell.node.name))
        }
      }
    }
  }

  @Test fun `a node with more room is subdivided deeper than a node with less`() {
    // Both subtrees have the same shape, and it's deeper than either can fully expand into, so the
    // only thing separating them is area: "big" holds 50x the weight of "small". Much beyond 50x
    // and "small" is too thin to draw at all, which is a different behaviour.
    val tree = NodeTree(
      Node(
        "root",
        children = listOf(
          uniformTree("big", depth = 10, breadth = 2, leafWeight = 50_000),
          uniformTree("small", depth = 10, breadth = 2, leafWeight = 1_000)
        )
      )
    )

    // A budget high enough that it never binds, isolating the area driven behaviour.
    val result = TreemapLayout<Node>(maxCells = Int.MAX_VALUE).layout(tree, viewport)

    val bigDepth = result.nodeCells.filter { it.node.name.startsWith("big") }.maxOf { it.depth }
    val smallDepth = result.nodeCells.filter { it.node.name.startsWith("small") }.maxOf { it.depth }
    assertThat(bigDepth).isGreaterThan(smallDepth)
  }

  @Test fun `subdivision stops when rectangles get too small`() {
    // A deep tree in a small viewport: depth has to bottom out well short of the tree's depth.
    val tree = NodeTree(uniformTree("root", depth = 10, breadth = 4, leafWeight = 1))

    val result = TreemapLayout<Node>().layout(tree, TreemapRect(0.0, 0.0, 200.0, 200.0))

    assertThat(result.cells.maxOf { it.depth }).isLessThan(10)
  }

  @Test fun `no cell is smaller than the minimum drawable size`() {
    val tree = NodeTree(uniformTree("root", depth = 6, breadth = 4, leafWeight = 1))
    val minDrawSize = 4.0

    val result = TreemapLayout<Node>(minDrawSize = minDrawSize).layout(tree, viewport)

    // The root takes the viewport as given; every cell the layout places must be drawable.
    result.cells.drop(1).forEach { cell ->
      assertThat(cell.rect.width).describedAs("width of $cell").isGreaterThanOrEqualTo(minDrawSize)
      assertThat(cell.rect.height).describedAs("height of $cell").isGreaterThanOrEqualTo(minDrawSize)
    }
  }

  @Test fun `cell count stays within the budget`() {
    val tree = NodeTree(uniformTree("root", depth = 8, breadth = 4, leafWeight = 1))

    val result = TreemapLayout<Node>(maxCells = 50).layout(tree, viewport)

    assertThat(result.cells.size).isLessThanOrEqualTo(50)
  }

  @Test fun `reaching the budget is reported as truncation`() {
    val tree = NodeTree(uniformTree("root", depth = 8, breadth = 4, leafWeight = 1))

    val result = TreemapLayout<Node>(maxCells = 50).layout(tree, viewport)

    assertThat(result.truncatedNodeCount).isGreaterThan(0)
  }

  @Test fun `a tree that fits is not reported as truncated`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(result.truncatedNodeCount).isEqualTo(0)
  }

  @Test fun `the budget is spent on the largest rectangles first`() {
    // Only one of the two subtrees can be subdivided within the budget, and it should be the
    // one holding almost all the weight.
    val tree = NodeTree(
      Node(
        "root",
        children = listOf(
          uniformTree("big", depth = 2, breadth = 4, leafWeight = 1_000_000),
          uniformTree("small", depth = 2, breadth = 4, leafWeight = 1_000)
        )
      )
    )

    val result = TreemapLayout<Node>(maxCells = 8).layout(tree, viewport)

    assertThat(result.names).contains("big.0")
    assertThat(result.names).doesNotContain("small.0")
  }

  @Test fun `layout is deterministic`() {
    val tree = NodeTree(uniformTree("root", depth = 5, breadth = 3, leafWeight = 7))

    val first = TreemapLayout<Node>().layout(tree, viewport)
    val second = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(first.cells).isEqualTo(second.cells)
  }

  @Test fun `zero weight children are left out`() {
    val tree = NodeTree(
      Node("root", children = listOf(Node("a", 10), Node("empty", 0)))
    )

    val result = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(result.names).doesNotContain("empty")
    // Not grouped either: a zero weight child has no area to stand for.
    assertThat(result.groups).isEmpty()
  }

  @Test fun `an empty viewport lays out only the root`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = TreemapLayout<Node>().layout(tree, TreemapRect(0.0, 0.0, 0.0, 0.0))

    assertThat(result.cells).hasSize(1)
  }

  @Test fun `a leaf root lays out only the root`() {
    val tree = NodeTree(Node("root", ownWeight = 10))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(result.cells).hasSize(1)
  }

  @Test fun `a cell knows the node it is nested in and where it ranks among its siblings`() {
    val tree = NodeTree(Node("root", children = listOf(Node("small", 5), Node("big", 10))))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    val root = result.nodeCells.first()
    assertThat(root.parent).isNull()
    assertThat(root.siblingIndex).isEqualTo(0)
    // Children are laid out heaviest first, whatever order the tree lists them in.
    val children = result.nodeCells.drop(1)
    assertThat(children.map { it.node.name }).containsExactly("big", "small")
    assertThat(children.map { it.parent?.name }).containsOnly("root")
    assertThat(children.map { it.siblingIndex }).containsExactly(0, 1)
  }

  @Test fun `a group knows the node whose children it stands for`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = TreemapLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    assertThat(result.groups.single().groupParent.name).isEqualTo("root")
  }

  @Test fun `hit testing returns the deepest cell at a point`() {
    val tree = NodeTree(uniformTree("root", depth = 3, breadth = 2, leafWeight = 1_000_000))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    val deepest = result.cells.maxBy { it.depth }
    val insideDeepest = TreemapPoint(
      x = deepest.rect.left + deepest.rect.width / 2,
      y = deepest.rect.top + deepest.rect.height / 2
    )
    assertThat(result.cellAt(insideDeepest)).isEqualTo(deepest)
  }

  @Test fun `hit testing near the edge of a container returns the container`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10), Node("b", 5))))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    // Children cover every pixel of the root, so its outline is the only part of it left to point at.
    val onTheEdge = TreemapPoint(viewport.width / 2, 2.0)
    assertThat(result.cellAt(onTheEdge, edgeGrab = 4.0)!!.node.name).isEqualTo("root")
    assertThat(result.cellAt(onTheEdge)!!.node.name).isNotEqualTo("root")
  }

  @Test fun `hit testing a gap in a subdivision returns the node holding it`() {
    // One child worth drawing and 20 000 that aren't, grouped into a cell of their own — and the group
    // is dotted around, so parts of the root come out covered by nothing.
    val children = listOf(Node("big", ownWeight = 80_000), Node("own", ownWeight = 0)) +
      List(20_000) { index -> Node("tiny$index", ownWeight = 1) }
    val tree = NodeTree(Node("root", ownWeight = 20_000, children = children))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    // Every point of the viewport answers something: a gap belongs to whatever was being subdivided.
    val points = (0..9).flatMap { x ->
      (0..9).map { y -> TreemapPoint(x * viewport.width / 10 + 1, y * viewport.height / 10 + 1) }
    }
    assertThat(points.map { result.cellAt(it) }).doesNotContainNull()
  }

  @Test fun `a node's own weight gets a rectangle inside it`() {
    // Half its weight is its own, so half of it is a cell that isn't one of its children.
    val tree = NodeTree(Node("root", ownWeight = 100, children = listOf(Node("child", 100))))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    val own = result.cells.single { it.subject is CellSubject.Own }
    assertThat((own.subject as CellSubject.Own).node.name).isEqualTo("root")
    assertThat(own.weight).isEqualTo(100)
    assertThat(own.rect.area / viewport.area).isCloseTo(0.5, withPercentage(1.0))
  }

  @Test fun `a rectangle is its share of the whole however deep it sits`() {
    // A chain 30 levels deep holding a tenth of the weight, which is the shape of an Android view
    // hierarchy: what used to happen is that 30 header strips ate the viewport before reaching it.
    var deep = Node("deep.30", ownWeight = 100_000)
    for (level in 29 downTo 0) {
      deep = Node("deep.$level", children = listOf(deep))
    }
    val tree = NodeTree(Node("root", children = listOf(Node("wide", 900_000), deep)))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    val leaf = result.nodeCells.single { it.node.name == "deep.30" }
    assertThat(leaf.depth).isEqualTo(31)
    assertThat(leaf.rect.area / viewport.area).isCloseTo(0.1, withPercentage(1.0))
  }

  @Test fun `laying out a subtree gives it the whole viewport`() {
    val big = uniformTree("big", depth = 3, breadth = 2, leafWeight = 1_000_000)
    val tree = NodeTree(
      Node("root", children = listOf(big, uniformTree("small", 3, 2, leafWeight = 1_000)))
    )

    val result = TreemapLayout<Node>().layout(tree, viewport, root = big)

    val rootCell = result.nodeCells.first()
    assertThat(rootCell.node.name).isEqualTo("big")
    assertThat(rootCell.rect).isEqualTo(viewport)
    assertThat(rootCell.depth).isEqualTo(0)
    assertThat(result.names.filter { it.startsWith("small") }).isEmpty()
  }

  @Test fun `laying out a subtree reveals depth the whole tree could not fit`() {
    val tree = NodeTree(uniformTree("root", depth = 8, breadth = 4, leafWeight = 1))
    val layout = TreemapLayout<Node>()

    val whole = layout.layout(tree, viewport)
    val zoomed = layout.layout(tree, viewport, root = tree.root.children.first())

    // Same viewport, so the zoomed subtree gets 4x the area per node and subdivides further.
    val deepestWholeName = whole.nodeCells.maxBy { it.depth }.node.name
    val deepestZoomedName = zoomed.nodeCells.maxBy { it.depth }.node.name
    assertThat(deepestZoomedName.count { it == '.' }).isGreaterThan(
      deepestWholeName.count { it == '.' }
    )
  }

  @Test fun `hit testing outside the treemap returns null`() {
    val tree = NodeTree(Node("root", children = listOf(Node("a", 10))))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(result.cellAt(TreemapPoint(-1.0, -1.0))).isNull()
    assertThat(result.cellAt(TreemapPoint(viewport.right + 1, viewport.bottom + 1))).isNull()
  }

  @Test fun `a node with far more children than the budget is still subdivided`() {
    // What the root of a real heap dump's dominator tree looks like: tens of thousands of children,
    // a handful of which hold most of the weight. Subdividing all or nothing left it as one
    // undivided rectangle with nothing to click.
    val children = List(30_000) { index -> Node("child$index", ownWeight = if (index < 4) 1_000_000 else 1) }
    val tree = NodeTree(Node("root", children = children))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(result.names).contains("child0", "child1", "child2", "child3")
    assertThat(result.cells.size).isLessThanOrEqualTo(5000)
  }

  @Test fun `zooming into the node holding a group draws the children it stood for`() {
    // The pile a rectangle's siblings are gathered into is what zooming into that rectangle is for, so
    // the node the viewport is rooted at draws as many children as it has room for rather than as many
    // as fit in a rectangle. Clicking a pile used to land on the same pile, one level in.
    val wide = Node("wide", children = List(50) { index -> Node("child$index", ownWeight = 100L - index) })
    val tree = NodeTree(Node("root", children = listOf(wide)))
    val layout = TreemapLayout<Node>(maxChildrenPerNode = 10)

    val nested = layout.layout(tree, viewport)
    val zoomed = layout.layout(tree, viewport, root = wide)

    assertThat(nested.groups.single().nodeCount).isEqualTo(40)
    assertThat(zoomed.groups).isEmpty()
    assertThat(zoomed.names).hasSize(51) // The wide node and all 50 of its children.
  }

  @Test fun `the node the viewport is rooted at still stops short of the cell budget`() {
    // Room for every one of them at a 3x3 square, so nothing but the budget stands in the way.
    val children = List(5_000) { index -> Node("child$index", ownWeight = 100L) }
    val tree = NodeTree(Node("root", children = children))

    val result = TreemapLayout<Node>(maxCells = 400).layout(tree, viewport)

    // Half the budget on one level at the most, so there is room left to draw inside what it drew.
    assertThat(result.names).hasSize(201)
    assertThat(result.groups.single().nodeCount).isEqualTo(4_800)
  }

  @Test fun `children past the per node limit are grouped into one rectangle`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = TreemapLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    assertThat(result.names).hasSize(11) // The root and its 10 largest children.
    val group = result.groups.single()
    assertThat(group.nodeCount).isEqualTo(40)
    assertThat(group.depth).isEqualTo(1)
    // The 40 children it stands for weigh 100 - 10 down to 100 - 49.
    assertThat(group.weight).isEqualTo((51L..90L).sum())
  }

  @Test fun `children too small to draw are grouped rather than dropped`() {
    // A fifth of the weight spread over 20 000 children, so each one is a hundred thousandth of the
    // viewport — under a 3x3 square — while together they're a fifth of it.
    val children = listOf(Node("big", ownWeight = 80_000)) +
      List(20_000) { index -> Node("tiny$index", ownWeight = 1) }
    val tree = NodeTree(Node("root", children = children))

    val result = TreemapLayout<Node>().layout(tree, viewport)

    assertThat(result.names).containsExactly("root", "big")
    val group = result.groups.single()
    assertThat(group.nodeCount).isEqualTo(20_000)
    assertThat(group.weight).isEqualTo(20_000L)
    assertThat(group.rect.area / viewport.area).isCloseTo(0.2, withPercentage(1.0))
  }

  @Test fun `a group and its siblings cover the whole area of a subdivided node`() {
    val children = List(50) { index -> Node("child$index", ownWeight = 100L - index) }
    val tree = NodeTree(Node("root", children = children))

    val result = TreemapLayout<Node>(maxChildrenPerNode = 10, maxRootChildren = 10)
      .layout(tree, viewport)

    val covered = result.cells.drop(1).sumOf { it.rect.area }
    assertThat(covered).isCloseTo(viewport.area, offset(1.0))
  }
}
