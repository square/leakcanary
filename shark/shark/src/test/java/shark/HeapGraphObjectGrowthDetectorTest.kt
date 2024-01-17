package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

class HeapGraphObjectGrowthDetectorTest {

  @Test
  fun `first traversal returns InitialHeapTraversal`() {
    val detector = newSimpleDetector()

    val heapTraversal = detector.findGrowingObjects(
      dump {
      },
      1,
      previousTraversal = NoHeapTraversalYet
    )

    assertThat(heapTraversal).isInstanceOf(InitialHeapTraversal::class.java)
  }

  @Test
  fun `second traversal returns HeapTraversalWithDiff`() {
    val detector = newSimpleDetector()
    val first = detector.findGrowingObjects(
      emptyHeapDump(),
      1,
      previousTraversal = NoHeapTraversalYet
    )

    val secondTraversal = detector.findGrowingObjects(
      emptyHeapDump(),
      1,
      previousTraversal = first
    )

    assertThat(secondTraversal).isInstanceOf(HeapTraversalWithDiff::class.java)
  }

  @Test
  fun `detect no growth on identical heaps`() {
    val detector = newSimpleDetector()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hi")
      } to 1,
      dump {
        classWithStringsInStaticField("Hi")
      } to 1
    )

    val traversal = detector.detectHeapGrowth(dumps)

    assertThat(traversal.growingNodes).isEmpty()
  }

  @Test
  fun `detect no growth on structurally identical heap`() {
    val detector = newSimpleDetector()

    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hi")
      } to 1,
      dump {
        classWithStringsInStaticField("Bonjour")
      } to 1
    )

    val traversal = detector.detectHeapGrowth(dumps)

    assertThat(traversal.growingNodes).isEmpty()
  }

  @Test
  fun `detect static field growth`() {
    val detector = newSimpleDetector()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      } to 1,
      dump {
        classWithStringsInStaticField("Hello", "World!")
      } to 1
    )

    val traversal = detector.detectHeapGrowth(dumps)

    assertThat(traversal.growingNodes).hasSize(1)
  }

  @Test
  fun `detect no growth if more loops than node increase`() {
    val detector = newSimpleDetector()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      } to 1,
      dump {
        classWithStringsInStaticField("Hello", "World!")
      } to 2
    )

    val traversal = detector.detectHeapGrowth(dumps)

    assertThat(traversal.growingNodes).isEmpty()
  }

  @Test
  fun `detect static field growth counts`() {
    val detector = newSimpleDetector()

    val heapDumpCount = 3
    val scenarioLoopCount = 7

    val dumps = (1..heapDumpCount).toList().map { heapDumpIndex ->
      val stringCount = heapDumpIndex * scenarioLoopCount
      val strings = (1..stringCount).toList().map { "Hi $it" }.toTypedArray()
      dump {
        classWithStringsInStaticField(*strings)
      } to 1
    }

    val traversal = detector.detectHeapGrowth(dumps)

    val growingNode = traversal.growingNodes.first()

    assertThat(growingNode.selfObjectCount).isEqualTo(1)
    assertThat(growingNode.childrenObjectCount).isEqualTo(heapDumpCount * scenarioLoopCount)
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(scenarioLoopCount)
    assertThat(growingNode.children).hasSize(1)
  }

  private fun HeapGraphObjectGrowthDetector.detectHeapGrowth(heapDumps: List<Pair<CloseableHeapGraph, Int>>): HeapTraversalWithDiff {
    return heapDumps.fold<Pair<CloseableHeapGraph, Int>, InputHeapTraversal>(
      initial = NoHeapTraversalYet
    ) { previous, (graph, count) ->
      findGrowingObjects(graph, count, previous)
    } as HeapTraversalWithDiff
  }

  private fun HprofWriterHelper.classWithStringsInStaticField(vararg strings: String) {
    clazz(
      "ClassWithStatics",
      staticFields = listOf("strings" to objectArray(*strings.map { string(it) }.toTypedArray()))
    )
  }

  private fun emptyHeapDump() = dump {}

  private fun dump(
    block: HprofWriterHelper.() -> Unit
  ): CloseableHeapGraph {
    return  dump(HprofHeader(), block).openHeapGraph()
  }

  private fun newSimpleDetector(): HeapGraphObjectGrowthDetector {
    val referenceReaderFactory = ActualMatchingReferenceReaderFactory(
      referenceMatchers = emptyList()
    )
    val gcRootProvider = GcRootProvider { graph ->
      graph.gcRoots.asSequence().map {
        GcRootReference(
          gcRoot = it,
          isLowPriority = false,
          matchedLibraryLeak = null
        )
      }
    }
    return HeapGraphObjectGrowthDetector(gcRootProvider, referenceReaderFactory)
  }
}
