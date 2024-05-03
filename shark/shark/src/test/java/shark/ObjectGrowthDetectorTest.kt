package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

class ObjectGrowthDetectorTest {

  @Test
  fun `first traversal returns InitialHeapTraversal`() {
    val detector = newSimpleDetector()

    val heapTraversal = detector.findGrowingObjects(
      heapGraph = dump {
      },
      previousTraversal = InitialState(scenarioLoopsPerGraph = 1),
    )

    assertThat(heapTraversal).isInstanceOf(FirstHeapTraversal::class.java)
  }

  @Test
  fun `second traversal returns HeapTraversalWithDiff`() {
    val detector = newSimpleDetector()
    val first = detector.findGrowingObjects(
      heapGraph = emptyHeapDump(),
      previousTraversal = InitialState(scenarioLoopsPerGraph = 1),
    )

    val secondTraversal = detector.findGrowingObjects(
      heapGraph = emptyHeapDump(),
      previousTraversal = first,
    )

    assertThat(secondTraversal).isInstanceOf(HeapGrowthTraversal::class.java)
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

    val growingNodes = detector.detectHeapGrowth(dumps)

    assertThat(growingNodes).isEmpty()
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

    val growingNodes = detector.detectHeapGrowth(dumps)

    assertThat(growingNodes).isEmpty()
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

    val growingNodes = detector.detectHeapGrowth(dumps)

    assertThat(growingNodes).hasSize(1)
  }

  @Test
  fun `detect no growth if more loops than node increase`() {
    val detector = newSimpleDetector()
    val dumps = listOf(
      dump {
        classWithStringsInStaticField("Hello")
      },
      dump {
        classWithStringsInStaticField("Hello", "World!")
      }
    )

    val growingNodes = detector.detectHeapGrowth(dumps, 2)

    assertThat(growingNodes).isEmpty()
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

    val growingNodes = detector.detectHeapGrowth(dumps)

    val growingNode = growingNodes.first()

    assertThat(growingNode.selfObjectCount).isEqualTo(1)
    assertThat(growingNode.childrenObjectCount).isEqualTo(heapDumpCount * scenarioLoopCount)
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(scenarioLoopCount)
    assertThat(growingNode.children).hasSize(1)
  }

  private fun ObjectGrowthDetector.detectHeapGrowth(
    heapDumps: List<CloseableHeapGraph>,
    scenarioLoopsPerGraph: Int = 1
  ): GrowingObjectNodes {
    return repeatingHeapGraph().findRepeatedlyGrowingObjects(
      initialState = InitialState(
        scenarioLoopsPerGraph = scenarioLoopsPerGraph,
        heapGraphCount = heapDumps.size
      ),
      heapGraphSequence = heapDumps.asSequence()
    ).growingObjects
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
    return dump(HprofHeader(), block).openHeapGraph()
  }

  private fun newSimpleDetector(): ObjectGrowthDetector {
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
    return ObjectGrowthDetector(gcRootProvider, referenceReaderFactory)
  }
}
