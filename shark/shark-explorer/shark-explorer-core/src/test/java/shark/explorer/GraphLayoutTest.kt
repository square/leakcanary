package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.ReferenceLocationType

/** Where [GraphLayout] puts the circles and the arrows of an expanded [ObjectGraph]. */
class GraphLayoutTest {

  private val layout = GraphLayout(columnWidth = 200.0, rowHeight = 40.0, nodeRadius = 8.0)

  @Test fun `the object the graph is rooted at sits at the origin`() {
    val result = layout.layout(graph(ROOT to listOf(2L, 3L)))

    assertThat(result.rootObjectId).isEqualTo(ROOT)
    assertThat(result.cellOf(ROOT).center).isEqualTo(TreemapPoint(0.0, 0.0))
  }

  @Test fun `what a node references is drawn in the column beside it, a row each`() {
    val result = layout.layout(graph(ROOT to listOf(2L, 3L)))

    assertThat(result.cellOf(2L).center.x).isEqualTo(200.0)
    assertThat(result.cellOf(3L).center.x).isEqualTo(200.0)
    assertThat(result.cellOf(3L).center.y - result.cellOf(2L).center.y).isEqualTo(40.0)
    assertThat(result.nodes.map { it.depth }).containsExactly(0, 1, 1)
  }

  @Test fun `a node is centred on what hangs off it`() {
    val result = layout.layout(graph(ROOT to listOf(2L, 3L)))

    val childYs = listOf(result.cellOf(2L).center.y, result.cellOf(3L).center.y)
    assertThat(result.cellOf(ROOT).center.y).isEqualTo(childYs.average())
  }

  @Test fun `expanding something above the root leaves the root where it was`() {
    val expanded = layout.layout(graph(ROOT to listOf(2L, 3L), 2L to listOf(4L, 5L)))

    // Two more rows opened up above the middle of the picture, and the object the reader is looking at
    // is still at the origin: a layout numbered from its top edge would have slid it down instead.
    assertThat(expanded.cellOf(ROOT).center).isEqualTo(TreemapPoint(0.0, 0.0))
    assertThat(expanded.cellOf(2L).center.y).isLessThan(0.0)
  }

  @Test fun `an object pointed at twice is drawn once, and the second arrow crosses the picture`() {
    val result = layout.layout(graph(ROOT to listOf(2L, 3L), 2L to listOf(4L), 3L to listOf(4L)))

    assertThat(result.nodes.count { it.subject.nodeOrNull() == 4L }).isEqualTo(1)
    // Hung below the first reference that reached it, and pointed at from the other one across the map.
    val toFour = result.edges.filter { it.reference.toObjectId == 4L }
    assertThat(toFour.map { it.reference.fromObjectId }).containsExactly(2L, 3L)
    assertThat(toFour.single { it.reference.fromObjectId == 2L }.isSpanning).isTrue()
    assertThat(toFour.single { it.reference.fromObjectId == 3L }.isSpanning).isFalse()
  }

  @Test fun `a reference running back into what holds it draws an arrow rather than a second circle`() {
    val result = layout.layout(graph(ROOT to listOf(2L), 2L to listOf(ROOT)))

    assertThat(result.nodes.map { it.subject.nodeOrNull() }).containsExactly(ROOT, 2L)
    assertThat(result.edges.map { it.reference.fromObjectId to it.reference.toObjectId })
      .containsExactly(ROOT to 2L, 2L to ROOT)
  }

  @Test fun `the references a node had no room for are one cell of their own, last`() {
    val result = layout.layout(graph(ROOT to listOf(2L), hiddenCount = 40))

    val leftover = result.nodes.single { it.subject is CellSubject.Group }
    assertThat(leftover.subject).isEqualTo(CellSubject.Group(parent = ROOT, nodeCount = 40))
    // Under everything the node was drawn with, in the same column as them, and no object itself.
    assertThat(leftover.depth).isEqualTo(1)
    assertThat(leftover.center.y).isGreaterThan(result.cellOf(2L).center.y)
    assertThat(leftover.drawn).isNull()
  }

  @Test fun `a collapsed node draws nothing below it`() {
    val graph = graph(ROOT to listOf(2L), 2L to listOf(3L)).collapsing(2L)

    val result = layout.layout(graph)

    assertThat(result.nodes.map { it.subject.nodeOrNull() }).containsExactly(ROOT, 2L)
    assertThat(result.cellOf(2L).isExpanded).isFalse()
  }

  @Test fun `a click lands on a circle and on the name beside it`() {
    val result = layout.layout(graph(ROOT to listOf(2L)))

    val cell = result.cellOf(2L)
    assertThat(result.cellAt(cell.center)).isEqualTo(cell)
    // The name is written to the right of the circle, and is as much a part of the node as it is.
    assertThat(result.cellAt(TreemapPoint(cell.bounds.right - 1, cell.center.y))).isEqualTo(cell)
    assertThat(result.cellAt(TreemapPoint(cell.bounds.right + 1, cell.center.y))).isNull()
  }

  @Test fun `the bounds cover everything drawn, above the root as well as below it`() {
    val result = layout.layout(graph(ROOT to listOf(2L, 3L), 2L to listOf(4L, 5L)))

    assertThat(result.bounds.top).isEqualTo(result.nodes.minOf { it.bounds.top })
    assertThat(result.bounds.bottom).isEqualTo(result.nodes.maxOf { it.bounds.bottom })
    assertThat(result.bounds.top).isLessThan(0.0)
  }

  @Test fun `a node whose object is not there is nothing to lay out`() {
    assertThat(layout.layout(ObjectGraph.EMPTY).nodes).isEmpty()
  }

  @Test fun `a node dominating what hangs off it says so`() {
    val result = layout.layout(graph(ROOT to listOf(2L)))

    assertThat(result.cellOf(ROOT).dominatesBelow).isTrue()
    assertThat(result.cellOf(2L).dominatesBelow).isFalse()
  }

  /** A graph with every listed node expanded and read, as the window would have filled it in. */
  private fun graph(
    vararg expansions: Pair<Long, List<Long>>,
    hiddenCount: Int = 0
  ): ObjectGraph = expansions.fold(ObjectGraph.rootedAt(drawnObject(ROOT))) { graph, (from, targets) ->
    graph.expanding(from).withReferences(
      ObjectReferences(
        fromObjectId = from,
        references = targets.map { target ->
          GraphReference(
            fromObjectId = from,
            toObjectId = target,
            reference = PathReference(
              name = "field$target",
              ownerClassName = "C$from",
              locationType = ReferenceLocationType.INSTANCE_FIELD
            ),
            isDominator = true
          )
        },
        objects = targets.map { drawnObject(it) },
        hiddenCount = if (from == ROOT) hiddenCount else 0
      )
    )
  }

  private fun drawnObject(objectId: Long) = GraphObject(
    objectId = objectId,
    className = "com.example.C$objectId",
    kind = HeapObjectKind.INSTANCE,
    strength = ReachabilityStrength.STRONG,
    retainedSize = 100L,
    retainedCount = 1,
    referenceCount = 1
  )

  private fun GraphLayoutResult.cellOf(objectId: Long): GraphNodeCell =
    nodes.single { it.subject.nodeOrNull() == objectId }

  private fun CellSubject<Long>.nodeOrNull(): Long? = (this as? CellSubject.Node)?.node

  private companion object {
    const val ROOT = 1L
  }
}
