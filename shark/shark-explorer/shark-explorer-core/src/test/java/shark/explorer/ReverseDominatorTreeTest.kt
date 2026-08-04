package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * The heap dump's domination read from the classes up: every object on the row of its class, and above each
 * row the classes of what dominates it.
 *
 * Asserted on a dump of three payload arrays, two of them held by instances of one class and one by an
 * instance of another, so that the arrays are one row and the row above it splits that row's width two ways.
 * Which is the whole shape of this tree in the smallest dump that has it.
 */
class ReverseDominatorTreeTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  @Test fun `the rows are the classes of every object of the heap dump`() {
    classRows { explorer, rows ->
      val classRows = rows.children(rows.root)

      // Heaviest first, as every level of either tree is handed out. The class objects are a row of their
      // own because this dump has no `java.lang.Class` for them to gather under, as no Android dump does.
      assertThat(rows.labelsOf(classRows)).containsExactly(
        "3 × Object[]",
        "$CLASS_OBJECT_COUNT × class objects",
        "2 × Tile",
        "1 × Solo"
      )
      assertThat(rows.summarize(rows.root).objectCount)
        .isEqualTo(classRows.sumOf { rows.summarize(it).objectCount })
      assertThat(explorer.tree.reverseTree).isSameAs(rows)
    }
  }

  @Test fun `the rows add up to the whole heap dump`() {
    classRows { explorer, rows ->
      // What makes a row's width its share of the heap: shallow bytes count every object exactly once, so
      // the bottom row of every column together is the whole dump — which is what the tree beside it
      // weighs at its own root. This dump has no object counted inside another, so it is exact here.
      assertThat(rows.weight(rows.root)).isEqualTo(explorer.tree.weight(explorer.tree.root))
      assertThat(rows.children(rows.root).sumOf { rows.weight(it) }).isEqualTo(rows.weight(rows.root))
    }
  }

  @Test fun `a class row is as wide as what its objects take up themselves`() {
    classRows { explorer, rows ->
      val arrays = explorer.tree.allSummaries().filter { it.label == "Object[]" }
      assertThat(arrays).hasSize(3)

      assertThat(rows.weight(rows.rowOf(rows.root, "3 × Object[]")))
        .isEqualTo(arrays.sumOf { it.shallowSize })
    }
  }

  @Test fun `the row above one is what dominates its objects, split by class`() {
    classRows { _, rows ->
      val arrays = rows.rowOf(rows.root, "3 × Object[]")
      val dominators = rows.children(arrays)

      assertThat(rows.labelsOf(dominators)).containsExactly("2 × Tile", "1 × Solo")
      // Two thirds of the arrays' bytes are held by the two tiles and the last third by the one other
      // instance, which is what makes this row a reading of where those bytes go.
      val payload = rows.weight(arrays) / 3
      assertThat(dominators.map { rows.weight(it) }).containsExactly(2 * payload, payload)
    }
  }

  @Test fun `a row's children cover it to the byte`() {
    classRows { _, rows ->
      // Every level of every column, which is what lets a row be read as a share of the row under it: a
      // stack drawn from this tree has no width left over anywhere.
      rows.everyRow().forEach { row ->
        val children = rows.children(row)
        if (children.isNotEmpty()) {
          assertThat(children.sumOf { rows.weight(it) })
            .describedAs(rows.label(row))
            .isEqualTo(rows.weight(row))
        }
      }
    }
  }

  @Test fun `a column stops where nothing in particular holds the objects`() {
    classRows { _, rows ->
      val tiles = rows.rowOf(rows.rowOf(rows.root, "3 × Object[]"), "2 × Tile")

      // Each tile is a GC root of its own, so what holds it is the heap dump rather than an object of it.
      val terminal = rows.children(tiles).single()
      assertThat(rows.label(terminal)).isEqualTo(ReverseDominatorTree.NO_OWNER_LABEL)
      assertThat(rows.summarize(terminal).kind).isEqualTo(ReverseNodeKind.NO_OWNER)
      // A row of its own rather than width left over, which is what keeps the row below it covered, and
      // the end of the column: there is nothing above what nothing holds.
      assertThat(rows.weight(terminal)).isEqualTo(rows.weight(tiles))
      assertThat(rows.children(terminal)).isEmpty()
    }
  }

  @Test fun `a column stops at the uncollected garbage as well`() {
    HeapExplorer.open(testFolder.uncollectedGarbageHeapDump()).use { explorer ->
      val rows = explorer.tree.reverseTree

      val payload = rows.rowOf(rows.root, "1 × Object[]")
      val forgotten = rows.rowOf(payload, "1 × Forgotten")
      val terminal = rows.children(forgotten).single()
      assertThat(rows.label(terminal)).isEqualTo(HeapDominatorTreemap.UNREACHABLE_LABEL)
      assertThat(rows.summarize(terminal).kind).isEqualTo(ReverseNodeKind.UNCOLLECTED_GARBAGE)
      assertThat(rows.summarize(terminal).strength).isEqualTo(ReachabilityStrength.UNREACHABLE)
      assertThat(rows.children(terminal)).isEmpty()
    }
  }

  @Test fun `a row knows the column it stands on, nearest first`() {
    classRows { _, rows ->
      val arrays = rows.rowOf(rows.root, "3 × Object[]")
      val tiles = rows.rowOf(arrays, "2 × Tile")

      val summary = rows.summarize(tiles)
      assertThat(summary.column.map { it.label })
        .containsExactly("3 × Object[]", HeapDominatorTreemap.ROOT_LABEL)
      assertThat(summary.column.map { it.nodeId }).containsExactly(arrays, rows.root)
      assertThat(summary.depth).isEqualTo(2)
      assertThat(summary.kind).isEqualTo(ReverseNodeKind.CLASS)
      assertThat(summary.className).isEqualTo("com.example.Tile")
      assertThat(summary.objectCount).isEqualTo(2)
      assertThat(summary.byteCount).isEqualTo(rows.weight(tiles))
    }
  }

  @Test fun `the whole heap dump is what every column stands on`() {
    classRows { explorer, rows ->
      val summary = rows.summarize(rows.root)

      assertThat(summary.kind).isEqualTo(ReverseNodeKind.WHOLE_HEAP_DUMP)
      assertThat(summary.label).isEqualTo(HeapDominatorTreemap.ROOT_LABEL)
      assertThat(summary.byteCount).isEqualTo(explorer.tree.weight(explorer.tree.root))
      assertThat(summary.column).isEmpty()
    }
  }

  @Test fun `a row is opened by zooming through the rows under it`() {
    classRows { _, rows ->
      val arrays = rows.rowOf(rows.root, "3 × Object[]")
      val tiles = rows.rowOf(arrays, "2 × Tile")

      assertThat(rows.pathToOpen(tiles)).containsExactly(rows.root, arrays, tiles)
      // A row nothing holds has nothing above it, so rooting the view there would draw one row and nothing
      // else: the view stops at the row under it, with it drawn inside.
      assertThat(rows.pathToOpen(rows.children(tiles).single()))
        .containsExactly(rows.root, arrays, tiles)
      assertThat(rows.pathToOpen(rows.root)).containsExactly(rows.root)
    }
  }

  @Test fun `a node of the tree read from the roots down is no node of this one`() {
    classRows { explorer, rows ->
      val objectId = explorer.tree.findByLabel("Solo").objectId
      val arrays = rows.rowOf(rows.root, "3 × Object[]")

      assertThat(objectId in rows).isFalse()
      assertThat(arrays in rows).isTrue()
      // The one node the two trees share, so that a path at the root is a path of either of them.
      assertThat(rows.root in rows).isTrue()
      assertThat(rows.root).isEqualTo(explorer.tree.root)
      assertThat(ReverseDominatorTree.isReverseNode(arrays)).isTrue()
      assertThat(ReverseDominatorTree.isReverseNode(objectId)).isFalse()
    }
  }

  @Test fun `asking this tree about an object says that it has no such row`() {
    classRows { explorer, rows ->
      val objectId = explorer.tree.findByLabel("Solo").objectId

      assertThatThrownBy { rows.weight(objectId) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("is no row of this tree")
    }
  }

  @Test fun `every cell of a stack laid out from it is a pile of objects`() {
    classRows { _, rows ->
      val result = StackLayout<Long>(rowsGoUp = true)
        .layout(rows, TreemapRect(0.0, 0.0, 1000.0, 800.0))

      val presented = rows.present(result.cells)
      // Which is what makes this a view of its own rather than another shape of the tree beside it: not one
      // cell here is an object whose fields can be read.
      assertThat(presented.map { it.content }).allMatch { it is CellContent.ObjectRow }
      assertThat(presented.map { it.label })
        .contains(HeapDominatorTreemap.ROOT_LABEL, "3 × Object[]", "2 × Tile")
    }
  }

  /** Opens [classRowsHeapDump] and hands the test its tree read from the classes up. */
  private fun classRows(block: (HeapExplorer, ReverseDominatorTree) -> Unit) {
    HeapExplorer.open(testFolder.classRowsHeapDump()).use { explorer ->
      block(explorer, explorer.tree.reverseTree)
    }
  }

  /** The one row above [below] with [rowLabel] on it, which is how these tests walk up a column. */
  private fun ReverseDominatorTree.rowOf(
    below: Long,
    rowLabel: String
  ): Long = children(below).single { label(it) == rowLabel }

  private fun ReverseDominatorTree.labelsOf(nodeIds: List<Long>): List<String> =
    nodeIds.map { label(it) }

  /** Every row of the tree, which reading them all is what expands them all. */
  private fun ReverseDominatorTree.everyRow(): List<Long> {
    val rows = mutableListOf<Long>()
    val toVisit = ArrayDeque(children(root))
    while (toVisit.isNotEmpty()) {
      val row = toVisit.removeFirst()
      rows += row
      toVisit += children(row)
    }
    return rows
  }

  /**
   * A heap dump where two instances of one class and one of another each hold an object array of the same
   * size, all three of one array class: so the arrays are one class row, and the row above it is two.
   */
  private fun TemporaryFolder.classRowsHeapDump(): File {
    val file = newFile("class-rows.hprof")
    file.dump {
      // One class for all three arrays, declared once: the `arrayClass` shorthand writes a class record
      // every time it is called, and three of those would be three classes and therefore three rows.
      val objectArrayClassId = arrayClass("java.lang.Object")
      val tileClassId = clazz(
        className = "com.example.Tile",
        fields = listOf("payload" to ReferenceHolder::class)
      )
      repeat(TILE_INSTANCE_COUNT) { index ->
        val payload = ReferenceHolder(objectArray(objectArrayClassId, LongArray(PAYLOAD_ELEMENT_COUNT)))
        val tile = instance(tileClassId, listOf(payload))
        gcRoot(JniGlobal(id = tile.value, jniGlobalRefId = index.toLong()))
      }
      val solo = "com.example.Solo" instance {
        field["payload"] =
          ReferenceHolder(objectArray(objectArrayClassId, LongArray(PAYLOAD_ELEMENT_COUNT)))
      }
      gcRoot(JniGlobal(id = solo.value, jniGlobalRefId = TILE_INSTANCE_COUNT.toLong()))
    }
    return file
  }

  private companion object {
    /** Two of them, so that the row above the arrays is one row of two objects and one of one. */
    const val TILE_INSTANCE_COUNT = 2

    /**
     * Every class the dump has an object of but the array class the payloads share: the two declared here,
     * and what the `dump { }` DSL writes whether or not anything of it is used.
     *
     * Asserted on because the row they land on is real — a heap dump without `java.lang.Class` has no class
     * for its class objects, which is a row of this tree like any other. See `GroupingClasses.classIdOf`.
     */
    const val CLASS_OBJECT_COUNT = 7
  }
}
