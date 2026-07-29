package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder.ReferenceHolder
import shark.dump

class HeapTreemapTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the root is the whole reachable heap`() {
    openTestHeapDump().use { treemap ->
      val rootChildren = treemap.children(treemap.root)

      assertThat(rootChildren).isNotEmpty()
      // The virtual root has no shallow size of its own, so it weighs exactly what it dominates.
      assertThat(treemap.weight(treemap.root))
        .isEqualTo(rootChildren.sumOf { treemap.weight(it) })
    }
  }

  @Test fun `children are ordered largest retained first`() {
    openTestHeapDump().use { treemap ->
      val weights = treemap.children(treemap.root).map { treemap.weight(it) }

      assertThat(weights).isEqualTo(weights.sortedDescending())
    }
  }

  @Test fun `the root is labelled rather than read from the heap`() {
    openTestHeapDump().use { treemap ->
      assertThat(treemap.label(treemap.root)).isEqualTo(HeapTreemap.ROOT_LABEL)
      assertThat(treemap.summarize(treemap.root).inspectorLabels).isEmpty()
    }
  }

  @Test fun `an instance is labelled with its simple class name`() {
    openTestHeapDump().use { treemap ->
      val holder = treemap.findByLabel("Holder")

      assertThat(holder.className).isEqualTo("com.example.Holder")
    }
  }

  @Test fun `an object retains what it dominates`() {
    openTestHeapDump().use { treemap ->
      val holder = treemap.findByLabel("Holder")
      val dominated = treemap.children(holder.objectId).map { treemap.summarize(it) }

      // The holder is the only path to its array and its name, so it retains both. A string's
      // char array is not a node of its own: Shark folds its size into the string instead.
      assertThat(dominated.map { it.label }).containsExactly("Object[]", "String")
      assertThat(holder.retainedCount).isEqualTo(3)
      assertThat(holder.retainedSize)
        .isEqualTo(holder.shallowSize + dominated.sumOf { it.retainedSize })
    }
  }

  @Test fun `a string instance summary carries its content`() {
    openTestHeapDump().use { treemap ->
      val strings = treemap.allSummaries().filter { it.className == "java.lang.String" }

      assertThat(strings.mapNotNull { it.stringValue }).contains("Kept alive by the holder")
    }
  }

  @Test fun `progress is reported for each step`() {
    val steps = mutableListOf<String>()

    openTestHeapDump(onProgress = { steps += it }).use {
      assertThat(steps).hasSize(2)
      assertThat(steps.first()).contains("heap.hprof")
      assertThat(steps.last()).contains("dominator tree")
    }
  }

  @Test fun `opening a file that is not a heap dump fails`() {
    val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }

    assertThatThrownBy { HeapTreemap.open(notAHeapDump) }
      .isInstanceOf(Exception::class.java)
  }

  /**
   * A heap dump where one instance is the only path to a large object array, so that the dominator
   * tree has an object retaining well more than its shallow size.
   */
  private fun openTestHeapDump(onProgress: (String) -> Unit = {}): HeapTreemap {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val payload = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(256)))
      val holder = "com.example.Holder" instance {
        field["payload"] = payload
        field["name"] = string("Kept alive by the holder")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return HeapTreemap.open(file, onProgress)
  }

  private fun HeapTreemap.allSummaries(): List<HeapObjectSummary> {
    val summaries = mutableListOf<HeapObjectSummary>()
    val toVisit = ArrayDeque(listOf(root))
    while (toVisit.isNotEmpty()) {
      val objectId = toVisit.removeFirst()
      summaries += summarize(objectId)
      toVisit += children(objectId)
    }
    return summaries
  }

  private fun HeapTreemap.findByLabel(label: String): HeapObjectSummary =
    allSummaries().single { it.label == label }
}
