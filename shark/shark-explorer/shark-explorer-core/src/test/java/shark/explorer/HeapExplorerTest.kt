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
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK

class HeapExplorerTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the root is the whole reachable heap`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val rootChildren = tree.children(tree.root)

      assertThat(rootChildren).isNotEmpty()
      // The virtual root has no shallow size of its own, so it weighs exactly what it dominates.
      assertThat(tree.weight(tree.root)).isEqualTo(rootChildren.sumOf { tree.weight(it) })
    }
  }

  @Test fun `children are ordered largest retained first`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val weights = tree.children(tree.root).map { tree.weight(it) }

      assertThat(weights).isEqualTo(weights.sortedDescending())
    }
  }

  @Test fun `the root is labelled rather than read from the heap`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())

      assertThat(tree.label(tree.root)).isEqualTo(HeapDominatorTreemap.ROOT_LABEL)
      assertThat(tree.summarize(tree.root).inspectorLabels).isEmpty()
    }
  }

  @Test fun `an instance is labelled with its simple class name`() {
    openTestHeapDump().use { explorer ->
      val holder = explorer.treeFor(emptySet()).findByLabel("Holder")

      assertThat(holder.className).isEqualTo("com.example.Holder")
    }
  }

  @Test fun `an object retains what it dominates`() {
    openTestHeapDump().use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val holder = tree.findByLabel("Holder")
      val dominated = tree.children(holder.objectId).map { tree.summarize(it) }

      // The holder is the only path to its array and its name, so it retains both. A string's
      // char array is not a node of its own: Shark folds its size into the string instead.
      assertThat(dominated.map { it.label }).containsExactly("Object[]", "String")
      assertThat(holder.retainedCount).isEqualTo(3)
      assertThat(holder.retainedSize)
        .isEqualTo(holder.shallowSize + dominated.sumOf { it.retainedSize })
    }
  }

  @Test fun `a string summary carries its content`() {
    openTestHeapDump().use { explorer ->
      val string = explorer.treeFor(emptySet()).findByLabel("String")

      assertThat(string.stringValue).isEqualTo("Kept alive by the holder")
    }
  }

  @Test fun `progress is reported for each step`() {
    val steps = mutableListOf<String>()

    openTestHeapDump(onProgress = { steps += it }).use { explorer ->
      explorer.treeFor(emptySet(), onProgress = { steps += it })
    }

    assertThat(steps).hasSize(3)
    assertThat(steps).allSatisfy { assertThat(it).isNotEmpty() }
  }

  @Test fun `opening a file that is not a heap dump fails`() {
    val notAHeapDump = testFolder.newFile("not-a-heap-dump.txt").apply { writeText("nope") }

    assertThatThrownBy { HeapExplorer.open(notAHeapDump) }
      .isInstanceOf(Exception::class.java)
  }

  @Test fun `an object only reachable through a weak reference is not retained`() {
    HeapExplorer.open(weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(emptySet())
      val labels = tree.allSummaries().map { it.label }

      assertThat(labels).contains("WeakReference")
      // 1024 ids at 4 bytes each: if a weak reference counted as retaining its referent, this array
      // would be the biggest thing in the treemap. It isn't in it at all.
      assertThat(labels).doesNotContain("Object[]")
      assertThat(tree.weight(tree.root)).isLessThan(PAYLOAD_BYTE_SIZE)
    }
  }

  @Test fun `following weak references nests the referent inside the weak reference`() {
    HeapExplorer.open(weaklyReachablePayloadHeapDump()).use { explorer ->
      val tree = explorer.treeFor(setOf(WEAK))
      val weakReference = tree.findByLabel("WeakReference")
      val dominated = tree.children(weakReference.objectId).map { tree.summarize(it) }

      // What makes a weakly reachable rectangle show up inside a strongly reachable one: the weak
      // reference itself is strongly reachable, and it dominates a referent that isn't.
      assertThat(weakReference.strength).isEqualTo(STRONG)
      assertThat(dominated.map { it.label }).containsExactly("Object[]")
      assertThat(dominated.single().strength).isEqualTo(WEAK)
      assertThat(tree.weight(tree.root)).isGreaterThan(PAYLOAD_BYTE_SIZE)
    }
  }

  @Test fun `the tree is reused when the strengths do not change`() {
    openTestHeapDump().use { explorer ->
      assertThat(explorer.treeFor(setOf(WEAK))).isSameAs(explorer.treeFor(setOf(WEAK)))
      assertThat(explorer.treeFor(emptySet())).isNotSameAs(explorer.treeFor(setOf(WEAK)))
    }
  }

  /**
   * A heap dump where one instance is the only path to a large object array, so that the dominator
   * tree has an object retaining well more than its shallow size.
   */
  private fun openTestHeapDump(onProgress: (String) -> Unit = {}): HeapExplorer {
    val file = testFolder.newFile("heap.hprof")
    file.dump {
      val payload = ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(256)))
      val holder = "com.example.Holder" instance {
        field["payload"] = payload
        field["name"] = string("Kept alive by the holder")
      }
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    }
    return HeapExplorer.open(file, onProgress)
  }

  /** A heap dump where a large object array is only reachable through a `WeakReference`. */
  private fun weaklyReachablePayloadHeapDump(): File {
    val file = testFolder.newFile("weakly-reachable.hprof")
    file.dump {
      val classes = referenceClasses()
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val weakReference = reference(classes.weakId, payload)
      gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 0))
    }
    return file
  }

  private fun HeapDominatorTreemap.findByLabel(label: String): HeapObjectSummary =
    allSummaries().single { it.label == label }

  private fun HeapDominatorTreemap.allSummaries(): List<HeapObjectSummary> {
    val summaries = mutableListOf<HeapObjectSummary>()
    val toVisit = ArrayDeque(listOf(root))
    while (toVisit.isNotEmpty()) {
      val objectId = toVisit.removeFirst()
      summaries += summarize(objectId)
      toVisit += children(objectId)
    }
    return summaries
  }

  companion object {
    private const val PAYLOAD_ELEMENT_COUNT = 1024

    /** Object ids are 4 bytes in a dump built by the test DSL. */
    private const val PAYLOAD_BYTE_SIZE = PAYLOAD_ELEMENT_COUNT * 4L
  }
}
