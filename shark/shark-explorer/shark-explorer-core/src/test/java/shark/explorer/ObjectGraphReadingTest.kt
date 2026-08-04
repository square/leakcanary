package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the tree hands the expandable graph: which object a circle stands for, and which references are
 * drawn as arrows leaving it.
 *
 * The other three shapes ask the tree what dominates what. This is the one that asks the heap dump how
 * an object is actually pointed at, so what it leaves out and what it names is worth pinning down.
 */
class ObjectGraphReadingTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the graph opens on the whole heap dump, which is no object`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      val root = tree.graphObject(tree.root)!!

      assertThat(root.className).isEqualTo(HeapDominatorTreemap.ROOT_LABEL)
      // Drawn hollow, and with what it retains, which is the whole dump.
      assertThat(root.kind).isNull()
      assertThat(root.retainedSize).isEqualTo(explorer.sizes.totalByteCount)
    }
  }

  @Test fun `what the whole heap dump points at is the tree's own children`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      val references = tree.referencesFrom(tree.root).references

      assertThat(references.map { tree.label(it.toObjectId) }).contains("Holder")
      // No field points at them, and being drawn there is the tree saying they are held from a GC root.
      assertThat(references).allMatch { it.reference == null }
      assertThat(references).allMatch { it.isDominator }
    }
  }

  @Test fun `an arrow is named after the field that holds the object`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val holder = tree.findByLabel("Holder")

      val read = tree.referencesFrom(holder.objectId)

      assertThat(read.references.map { it.reference?.name }).containsExactly("payload", "name")
      assertThat(read.references.map { it.reference?.ownerClassName }).containsOnly("Holder")
      // Heaviest first, which is what a reader following bytes across a picture is after.
      assertThat(read.references.map { tree.label(it.toObjectId) })
        .containsExactly("Object[]", "String")
    }
  }

  @Test fun `an arrow says whether it is why the object is still in memory`() {
    HeapExplorer.open(testFolder.cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val tile = tree.findByLabel("Tile")
      val view = tree.findByLabel("View")

      val fromTile = tree.referencesFrom(tile.objectId).references
      val fromView = tree.referencesFrom(view.objectId).references

      // Nothing else reaches the view, so the tile holding it is why it is still there.
      assertThat(fromTile.single { tree.label(it.toObjectId) == "View" }.isDominator).isTrue()
      // The payload is reached through the wrapper as well, so the view is only one of the ways it is
      // held — which is exactly what a treemap can't draw and what this shape exists to say.
      assertThat(fromView.single { tree.label(it.toObjectId) == "Object[]" }.isDominator).isFalse()
    }
  }

  @Test fun `a string's characters are folded into it rather than drawn under it`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree
      val name = tree.findByLabel("String")

      // The size calculator folds a string's characters into the string, so the tree has no node for
      // them: nothing to draw a circle for, and nothing that could be asked about one.
      assertThat(tree.referencesFrom(name.objectId).references).isEmpty()
      // Which is also what says the circle is a dead end rather than something worth pressing.
      assertThat(tree.graphObject(name.objectId)!!.referenceCount).isZero()
    }
  }

  @Test fun `only the heaviest references are drawn, and the rest are counted`() {
    HeapExplorer.open(testFolder.cachedPayloadHeapDump()).use { explorer ->
      val tree = explorer.tree
      val tile = tree.findByLabel("Tile")

      val read = tree.referencesFrom(tile.objectId, limit = 1)

      assertThat(read.references).hasSize(1)
      assertThat(read.objects).hasSize(1)
      // The one left out is counted rather than dropped, which is the cell the reader presses for more.
      assertThat(read.hiddenCount).isEqualTo(1)
    }
  }

  @Test fun `a node the tree doesn't have is nothing to draw`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      assertThat(tree.graphObject(NOT_A_NODE)).isNull()
      assertThat(tree.referencesFrom(NOT_A_NODE).references).isEmpty()
    }
  }

  private companion object {
    /** An address no synthetic heap dump hands out, which is what a click on a stale node lands on. */
    const val NOT_A_NODE = 0xDEADBEEFL
  }
}
