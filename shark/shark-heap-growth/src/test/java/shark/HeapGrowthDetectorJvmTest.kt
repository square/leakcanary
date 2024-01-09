package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
    val detector = simpleDetector().live()

    val emptyScenario = {}

    val heapTraversal = detector.detectRepeatedHeapGrowth(emptyScenario)

    assertThat(heapTraversal.growingNodes).isEmpty()
  }

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = simpleDetector().live()

    val heapTraversal = detector.detectRepeatedHeapGrowth {
      leakies += Leaky()
    }

    assertThat(heapTraversal.growingNodes).isNotEmpty
  }

  @Test
  fun `string leak increase leads to heap growth`() {
    val detector = simpleDetector().live()

    var index = 0
    val heapTraversal = detector.detectRepeatedHeapGrowth {
      stringLeaks += "Yo ${++index}"
    }

    assertThat(heapTraversal.growingNodes).isNotEmpty
  }

  @Test
  fun `leak increase that ends leads to no heap growth`() {
    val maxHeapDumps = 10
    val stopLeakingIndex = maxHeapDumps / 2
    val detector = simpleDetector().live(maxHeapDumps = maxHeapDumps)

    var index = 0
    val heapTraversal = detector.detectRepeatedHeapGrowth {
      if (++index < stopLeakingIndex) {
        leakies += Leaky()
      }
    }

    assertThat(heapTraversal.growingNodes).isEmpty()
  }

  @Test
  fun `multiple leaky scenarios per dump leads to heap growth`() {
    val scenarioLoopsPerDump = 5
    val detector = simpleDetector().live(scenarioLoopsPerDump = scenarioLoopsPerDump)

    val heapTraversal = detector.detectRepeatedHeapGrowth {
      leakies += Leaky()
    }

    assertThat(heapTraversal.growingNodes).hasSize(1)

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(scenarioLoopsPerDump)
  }

  @Test
  fun `custom leaky linked list leads to heap growth`() {
    val detector = simpleDetector().live()

    val heapTraversal = detector.detectRepeatedHeapGrowth {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }

    assertThat(heapTraversal.growingNodes).isNotEmpty
  }

  @Test
  fun `custom leaky linked list reports descendant to root as flattened collection`() {
    val detector = simpleDetector().live()

    val heapTraversal = detector.detectRepeatedHeapGrowth {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }

    assertThat(heapTraversal.growingNodes).hasSize(1)

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.children.size).isEqualTo(1)
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(4)
  }

  @Test
  fun `growth along shared sub paths reported as single growth of shortest path`() {
    val detector = simpleDetector().live()

    val heapTraversal = detector.detectRepeatedHeapGrowth {
      multiLeakies += MultiLeaky()
    }

    assertThat(heapTraversal.growingNodes).hasSize(1)

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.nodeAndEdgeName).contains("ArrayList")
  }

  @Test
  fun `OpenJdk HashMap virtualized as array`() {
    val detector = openJdkDetector().live()

    var index = 0
    val heapTraversal = detector.detectRepeatedHeapGrowth {
      leakyHashMap["key${++index}"] = Leaky()
    }

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.nodeAndEdgeName).contains("leakyHashMap")
  }

  @Test
  fun `OpenJdk ArrayList virtualized as array`() {
    val detector = openJdkDetector().live()

    val heapTraversal = detector.detectRepeatedHeapGrowth {
      leakies += Leaky()
    }

    val growingNode = heapTraversal.growingNodes.first()
    assertThat(growingNode.nodeAndEdgeName).contains("leakies")
  }

  private fun DiffingHeapGrowthDetector.live(
    scenarioLoopsPerDump: Int = 1,
    maxHeapDumps: Int = 5
  ): LiveHeapGrowthDetector {
    return LiveHeapGrowthDetector(maxHeapDumps, ::dumpHeapGraph, scenarioLoopsPerDump, LoopingHeapGrowthDetector(this))
  }

  private fun simpleDetector(): DiffingHeapGrowthDetector {
    val referenceMatchers = JdkReferenceMatchers.defaults + HeapTraversal.ignoredReferences
    val referenceReaderFactory = ActualMatchingReferenceReaderFactory(referenceMatchers)
    val gcRootProvider = MatchingGcRootProvider(referenceMatchers)
    return DiffingHeapGrowthDetector(referenceReaderFactory, gcRootProvider)
  }

  private fun openJdkDetector(): DiffingHeapGrowthDetector {
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
    return DiffingHeapGrowthDetector(referenceReaderFactory, gcRootProvider)
  }

  private fun dumpHeapGraph(): CloseableHeapGraph {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "${System.nanoTime()}.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile.openHeapGraph()
  }
}
