package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.ReferenceLocationType

/**
 * What expanding, collapsing and paging do to the picture, with no heap dump behind them: the whole
 * point of [ObjectGraph] being state of its own is that the answers here are the same whether the reads
 * take a microsecond or a second.
 */
class ObjectGraphTest {

  @Test fun `a graph opens on its root, expanded and waiting to be read`() {
    val graph = ObjectGraph.rootedAt(drawnObject(ROOT, referenceCount = 2))

    assertThat(graph.isEmpty).isFalse()
    assertThat(graph.isExpanded(ROOT)).isTrue()
    // Expanded but not read, which is what has the window read the heap dump for it.
    assertThat(graph.pendingObjectId).isEqualTo(ROOT)
    assertThat(graph.referencesFrom(ROOT)).isEmpty()
  }

  @Test fun `folding a read in draws the arrows and what they point at`() {
    val graph = rootedGraph().reading(ROOT, 2L, 3L)

    assertThat(graph.referencesFrom(ROOT).map { it.toObjectId }).containsExactly(2L, 3L)
    assertThat(graph.objectOf(2L)?.className).isEqualTo("com.example.C2")
    // Nothing else was expanded, so there is nothing left to read.
    assertThat(graph.pendingObjectId).isNull()
  }

  @Test fun `expanding a node is what asks for its references`() {
    val graph = rootedGraph().reading(ROOT, 2L).expanding(2L)

    assertThat(graph.pendingObjectId).isEqualTo(2L)
    // Nothing hangs off it until that read comes back, and the click drew the circle all the same.
    assertThat(graph.referencesFrom(2L)).isEmpty()
  }

  @Test fun `collapsing takes what a node references off the picture`() {
    val graph = rootedGraph().reading(ROOT, 2L).expanding(2L).reading(2L, 3L)

    val collapsed = graph.collapsing(2L)

    assertThat(collapsed.referencesFrom(2L)).isEmpty()
    assertThat(collapsed.hiddenReferenceCountOf(2L)).isZero()
    // The circle itself is still there: it is what the reader collapsed, not something they left.
    assertThat(collapsed.objectOf(2L)).isNotNull
  }

  @Test fun `opening a node again costs no read`() {
    val graph = rootedGraph().reading(ROOT, 2L).expanding(2L).reading(2L, 3L)

    val reopened = graph.collapsing(2L).expanding(2L)

    assertThat(reopened.pendingObjectId).isNull()
    assertThat(reopened.referencesFrom(2L).map { it.toObjectId }).containsExactly(3L)
  }

  @Test fun `a node with more references than were drawn asks for another page`() {
    val graph = rootedGraph().reading(ROOT, 2L, hiddenCount = 40)

    assertThat(graph.hiddenReferenceCountOf(ROOT)).isEqualTo(40)
    assertThat(graph.referenceLimitOf(ROOT)).isEqualTo(ObjectGraph.REFERENCES_PER_PAGE)

    val more = graph.showingMoreOf(ROOT)

    assertThat(more.referenceLimitOf(ROOT)).isEqualTo(2 * ObjectGraph.REFERENCES_PER_PAGE)
    // Which is the same node pending again, this time for twice as many.
    assertThat(more.pendingObjectId).isEqualTo(ROOT)
  }

  @Test fun `a node drawn with everything it references is never pending again`() {
    val graph = rootedGraph().reading(ROOT, 2L)

    // Nothing was left out, so there is no cell to press and nothing another page would add.
    assertThat(graph.showingMoreOf(ROOT).pendingObjectId).isNull()
  }

  @Test fun `pressing a circle expands it, and pressing it again collapses it`() {
    val graph = rootedGraph().reading(ROOT, 2L)
    val cell = cellOf(CellSubject.Node(node = 2L, parent = ROOT, siblingIndex = 0))

    val expanded = graph.pressing(cell)

    assertThat(expanded.isExpanded(2L)).isTrue()
    assertThat(expanded.pressing(cell.copy(isExpanded = true)).isExpanded(2L)).isFalse()
  }

  @Test fun `pressing the cell counting what was left out draws another page of it`() {
    val graph = rootedGraph().reading(ROOT, 2L, hiddenCount = 40)
    val cell = cellOf(CellSubject.Group(parent = ROOT, nodeCount = 40))

    assertThat(graph.pressing(cell).referenceLimitOf(ROOT))
      .isEqualTo(2 * ObjectGraph.REFERENCES_PER_PAGE)
  }

  private fun rootedGraph() = ObjectGraph.rootedAt(drawnObject(ROOT, referenceCount = 2))

  /** One expansion's worth of read, as [HeapDominatorTreemap.referencesFrom] would have answered it. */
  private fun ObjectGraph.reading(
    from: Long,
    vararg targets: Long,
    hiddenCount: Int = 0
  ): ObjectGraph = withReferences(
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
      hiddenCount = hiddenCount
    )
  )

  private fun drawnObject(
    objectId: Long,
    referenceCount: Int = 0
  ) = GraphObject(
    objectId = objectId,
    className = "com.example.C$objectId",
    kind = HeapObjectKind.INSTANCE,
    strength = ReachabilityStrength.STRONG,
    retainedSize = 100L * objectId,
    retainedCount = 1,
    referenceCount = referenceCount
  )

  private fun cellOf(subject: CellSubject<Long>) = GraphNodeCell(
    subject = subject,
    drawn = null,
    center = TreemapPoint(0.0, 0.0),
    bounds = TreemapRect(0.0, 0.0, 0.0, 0.0),
    depth = 1,
    weight = 0L,
    isExpanded = false,
    dominatesBelow = false
  )

  private companion object {
    const val ROOT = 1L
  }
}
