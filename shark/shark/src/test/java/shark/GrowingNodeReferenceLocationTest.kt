package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * Tests that [ObjectGrowthDetector] keeps the structured [ReferenceLocation] of the reference each
 * node was reached through, next to the display string that is [ShortestPathObjectNode.name].
 */
class GrowingNodeReferenceLocationTest {

  @Test
  fun `growing node exposes the reference it was reached through as structured data`() {
    val growingObject = findGrowingObjects().growingObjects.single()

    // The display name only carries the simple name of the owning class.
    assertThat(growingObject.name).startsWith("STATIC_FIELD Retainer.strings")
    assertThat(growingObject.reference).isEqualTo(
      ReferenceLocation(
        locationType = ReferenceLocationType.STATIC_FIELD,
        owningClassName = "com.example.Retainer",
        referenceName = "strings",
      )
    )
  }

  @Test
  fun `nodes not reached through a reference have no reference`() {
    val tree = findGrowingObjects().shortestPathTree

    assertThat(tree.reference).isNull()
    // The gc root nodes right below the tree root.
    assertThat(tree.children.map { it.reference }).isNotEmpty.containsOnlyNulls()
    // Everything below those is reached through a reference.
    assertThat(tree.children.flatMap { it.children }.map { it.reference })
      .isNotEmpty
      .doesNotContainNull()
  }

  @Test
  fun `array entry node exposes the array class as the owning class`() {
    val growingObject = findGrowingObjects().growingObjects.single()

    val arrayEntry = growingObject.children.single().reference!!
    assertThat(arrayEntry.locationType).isEqualTo(ReferenceLocationType.ARRAY_ENTRY)
    assertThat(arrayEntry.owningClassName).isEqualTo("java.lang.Object[]")
  }

  /**
   * Runs the detector over 3 heap dumps of a static field holding a growing array of strings, and
   * returns the resulting diff.
   */
  private fun findGrowingObjects(): HeapDiff {
    val detector = ObjectGrowthDetector.forJvmHeap()
    var traversal: HeapTraversalInput = InitialState(scenarioLoopsPerGraph = 1)
    (1..3).forEach { heapDumpIndex ->
      val strings = (1..heapDumpIndex).map { "Hi $it" }
      dump {
        clazz(
          "com.example.Retainer",
          staticFields = listOf(
            "strings" to objectArray(*strings.map { string(it) }.toTypedArray())
          )
        )
      }.use { heapGraph ->
        traversal = detector.findGrowingObjects(heapGraph, traversal)
      }
    }
    return traversal as HeapDiff
  }

  private fun dump(block: HprofWriterHelper.() -> Unit): CloseableHeapGraph {
    return dump(HprofHeader(), block).openHeapGraph()
  }
}
