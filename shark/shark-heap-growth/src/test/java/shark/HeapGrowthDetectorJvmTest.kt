package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.DiffingHeapGrowthDetector.HeapDumpAfterLoopingScenario
import shark.HprofHeapGraph.Companion.openHeapGraph

class HeapGrowthDetectorJvmTest {

  class Leaky

  class MultiLeaky {
    val leaky = Leaky() to Leaky()
  }

  class CustomLinkedList(var next: CustomLinkedList? = null)

  @get:Rule
  val testFolder = TemporaryFolder()

  val leakies = mutableListOf<Leaky>()

  val stringLeaks = mutableListOf<String>()

  var customLeakyLinkedList = CustomLinkedList()

  val leakyHashMap = HashMap<String, Leaky>()

  val multiLeakies = mutableListOf<MultiLeaky>()

  @Test
  fun `empty scenario leads to no heap growth`() {
    val detector = simpleDetector()

    val heapDumper = LoopingHeapDumper(
      maxHeapDumps = 10,
      heapGraphProvider = this::dumpHeapGraph,
      scenarioLoopsPerDump = 1
    )

    val emptyScenario = {}

    val dumps = heapDumper.dumpHeapRepeated(emptyScenario)

    val heapTraversal = detector.repeatDiffsWhileGrowing(dumps)

    assertThat(heapTraversal.growing).isFalse
  }

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = simpleDetector()

    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      leakies += Leaky()
    })

    assertThat(heapTraversal.growing).isTrue
  }

  @Test
  fun `string leak increase leads to heap growth`() {
    val detector = simpleDetector()

    var index = 0
    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      stringLeaks += "Yo ${++index}"
    })

    assertThat(heapTraversal.growing).isTrue
  }

  @Test
  fun `leak increase that ends leads to no heap growth`() {
    val detector = simpleDetector()

    val maxHeapDumps = 10
    val stopLeakingIndex = maxHeapDumps / 2

    var index = 0
    val heapTraversal =
      detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps(maxHeapDumps = maxHeapDumps) {
        if (++index < stopLeakingIndex) {
          leakies += Leaky()
        }
      })

    assertThat(heapTraversal.growing).isFalse
  }

  @Test
  fun `multiple leaky scenarios per dump leads to heap growth`() {
    val detector = simpleDetector()

    val scenarioLoopsPerDump = 5

    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps(scenarioLoopsPerDump) {
      leakies += Leaky()
    })

    assertThat(heapTraversal.growing).isTrue
    assertThat(heapTraversal.growingNodes).hasSize(1)

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(scenarioLoopsPerDump)
  }

  @Test
  fun `custom leaky linked list leads to heap growth`() {
    val detector = simpleDetector()

    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    })

    assertThat(heapTraversal.growing).isTrue
  }

  @Test
  fun `custom leaky linked list reports descendant to root as flattened collection`() {
    val detector = simpleDetector()

    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    })

    assertThat(heapTraversal.growingNodes).hasSize(1)

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.children.size).isEqualTo(1)
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(4)
  }

  @Test
  fun `growth along shared sub paths reported as single growth of shortest path`() {
    val detector = simpleDetector()

    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      multiLeakies += MultiLeaky()
    })

    assertThat(heapTraversal.growingNodes).hasSize(1)

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.nodeAndEdgeName).contains("ArrayList")
  }

  @Test
  fun `OpenJdk HashMap virtualized as array`() {
    val detector = openJdkDetector()

    var index = 0
    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      leakyHashMap["key${++index}"] = Leaky()
    })

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.nodeAndEdgeName).contains("leakyHashMap")
  }

  @Test
  fun `OpenJdk ArrayList virtualized as array`() {
    val detector = openJdkDetector()

    val heapTraversal = detector.repeatDiffsWhileGrowing(sequenceOfHeapDumps {
      leakies += Leaky()
    })

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.nodeAndEdgeName).contains("leakies")
  }

  private fun sequenceOfHeapDumps(
    scenarioLoopsPerDump: Int = 1,
    maxHeapDumps: Int = 5,
    loopingScenario: () -> Unit,
  ): Sequence<HeapDumpAfterLoopingScenario> {
    val heapDumper = LoopingHeapDumper(
      maxHeapDumps = maxHeapDumps,
      heapGraphProvider = this::dumpHeapGraph,
      scenarioLoopsPerDump = scenarioLoopsPerDump
    )
    return heapDumper.dumpHeapRepeated(loopingScenario)
  }

  private fun simpleDetector(): LoopingHeapGrowthDetector {
    val referenceMatchers = JdkReferenceMatchers.defaults + HeapTraversal.ignoredReferences

    val referenceReaderFactory = ActualMatchingReferenceReaderFactory(referenceMatchers)
    val gcRootProvider = MatchingGcRootProvider(referenceMatchers)
    return LoopingHeapGrowthDetector(
      DiffingHeapGrowthDetector(referenceReaderFactory, gcRootProvider)
    )
  }

  private fun openJdkDetector(): LoopingHeapGrowthDetector {
    val referenceMatchers = JdkReferenceMatchers.defaults + HeapTraversal.ignoredReferences

    val referenceReaderFactory = VirtualizingMatchingReferenceReaderFactory(
      referenceMatchers = referenceMatchers,
      virtualRefReadersFactory = { graph ->
        listOf(
          JavaLocalReferenceReader(graph, referenceMatchers),
        ) +
          OpenJdkInstanceRefReaders.values().mapNotNull { it.create(graph) }
      }
    )

    val gcRootProvider = MatchingGcRootProvider(referenceMatchers)
    return LoopingHeapGrowthDetector(
      DiffingHeapGrowthDetector(referenceReaderFactory, gcRootProvider)
    )
  }

  private fun dumpHeapGraph(): CloseableHeapGraph {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "${System.nanoTime()}.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile.openHeapGraph()
  }
}
