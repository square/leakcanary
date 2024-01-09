package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.DiffingHeapGrowthDetector.HeapDumpAfterLoopingScenario
import shark.HprofHeapGraph.Companion.openHeapGraph

class HeapGrowthDetectorFakeDumpTest {

  @Test
  fun `first traversal returns InitialHeapTraversal`() {
    val detector = newSimpleDetector()

    val heapTraversal = detector.detectHeapGrowth(
      heapDump = dump {
      },
      previousTraversal = NoHeapTraversalYet
    )

    assertThat(heapTraversal).isInstanceOf(InitialHeapTraversal::class.java)
  }

  @Test
  fun `second traversal returns HeapTraversalWithDiff`() {
    val detector = newSimpleDetector()
    val first = detector.detectHeapGrowth(
      heapDump = emptyHeapDump(),
      previousTraversal = NoHeapTraversalYet
    )

    val secondTraversal = detector.detectHeapGrowth(
      heapDump = emptyHeapDump(),
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
      },
      dump {
        classWithStringsInStaticField("Hi")
      }
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
      },
      dump {
        classWithStringsInStaticField("Bonjour")
      }
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
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      }
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
      },
      dump(2) {
        classWithStringsInStaticField("Hello", "World!")
      }
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
      }
    }

    val traversal = detector.detectHeapGrowth(dumps)

    val growingNode = traversal.growingNodes.first()

    assertThat(growingNode.selfObjectCount).isEqualTo(1)
    assertThat(growingNode.childrenObjectCount).isEqualTo(heapDumpCount * scenarioLoopCount)
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(scenarioLoopCount)
    assertThat(growingNode.children).hasSize(1)
  }

  private fun DiffingHeapGrowthDetector.detectHeapGrowth(heapDumps: List<HeapDumpAfterLoopingScenario>): HeapTraversalWithDiff {
    return heapDumps.fold<HeapDumpAfterLoopingScenario, InputHeapTraversal>(
      initial = NoHeapTraversalYet
    ) { previous, dump ->
      detectHeapGrowth(dump, previous)
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
    scenarioLoopCount: Int = 1,
    block: HprofWriterHelper.() -> Unit
  ): HeapDumpAfterLoopingScenario {
    return HeapDumpAfterLoopingScenario(
      heapGraph = dump(HprofHeader(), block).openHeapGraph(),
      scenarioLoopCount = scenarioLoopCount
    )
  }

  private fun newSimpleDetector(): DiffingHeapGrowthDetector {
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
    return DiffingHeapGrowthDetector(referenceReaderFactory, gcRootProvider)
  }
}
