package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph

class LiveObjectGrowthDetectorTest {

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
    val detector = simpleDetector().fromScenario()

    val emptyScenario = {}

    val growingNodes = detector.findRepeatedlyGrowingObjects(roundTripScenario = emptyScenario)
      .growingObjects

    assertThat(growingNodes).isEmpty()
  }

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = simpleDetector().fromScenario()

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakies += Leaky()
    }.growingObjects

    assertThat(growingNodes).isNotEmpty
  }

  @Test
  fun `string leak increase leads to heap growth`() {
    val detector = simpleDetector().fromScenario()

    var index = 0
    val growingNodes = detector.findRepeatedlyGrowingObjects {
      stringLeaks += "Yo ${++index}"
    }.growingObjects

    assertThat(growingNodes).isNotEmpty
  }

  @Test
  fun `leak increase that ends leads to no heap growth`() {
    val maxHeapDumps = 10
    val stopLeakingIndex = maxHeapDumps / 2
    val detector = simpleDetector().fromScenario(maxHeapDumps = maxHeapDumps)

    var index = 0
    val growingNodes = detector.findRepeatedlyGrowingObjects {
      if (++index < stopLeakingIndex) {
        leakies += Leaky()
      }
    }.growingObjects

    assertThat(growingNodes).isEmpty()
  }

  @Test
  fun `multiple leaky scenarios per dump leads to heap growth`() {
    val scenarioLoopsPerDump = 5
    val detector =
      simpleDetector().fromScenario(scenarioLoopsPerDump = scenarioLoopsPerDump)

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakies += Leaky()
    }.growingObjects

    assertThat(growingNodes).hasSize(1)

    val growingNode = growingNodes.first()
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(scenarioLoopsPerDump)
  }

  @Test
  fun `custom leaky linked list leads to heap growth`() {
    val detector = simpleDetector().fromScenario()

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }.growingObjects

    assertThat(growingNodes).isNotEmpty
  }

  @Test
  fun `custom leaky linked list reports descendant to root as flattened collection`() {
    val detector = simpleDetector().fromScenario()

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }.growingObjects

    assertThat(growingNodes).hasSize(1)

    val growingNode = growingNodes.first()
    assertThat(growingNode.children.size).isEqualTo(1)
    assertThat(growingNode.childrenObjectCountIncrease).isEqualTo(4)
  }

  @Test
  fun `growth along shared sub paths reported as single growth of shortest path`() {
    val detector = simpleDetector().fromScenario()

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      multiLeakies += MultiLeaky()
    }.growingObjects

    assertThat(growingNodes).hasSize(1)

    val growingNode = growingNodes.first()
    assertThat(growingNode.name).contains("ArrayList")
  }

  @Test
  fun `OpenJdk HashMap virtualized as array`() {
    val detector = openJdkDetector().fromScenario(maxHeapDumps = 5)

    var index = 0
    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakyHashMap["key${++index}"] = Leaky()
    }.growingObjects

    val growingNode = growingNodes.first()
    assertThat(growingNode.name).contains("leakyHashMap")
  }

  @Test
  fun `OpenJdk ArrayList virtualized as array`() {
    val detector = openJdkDetector().fromScenario()

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakies += Leaky()
    }.growingObjects

    val growingNode = growingNodes.first()
    assertThat(growingNode.name).contains("leakies")
  }

  private fun ObjectGrowthDetector.fromScenario(
    scenarioLoopsPerDump: Int = 1,
    maxHeapDumps: Int = 5
  ): RepeatingScenarioObjectGrowthDetector {
    return repeatingScenario(
      heapGraphProvider = ::dumpHeapGraph,
      maxHeapDumps = maxHeapDumps,
      scenarioLoopsPerDump = scenarioLoopsPerDump
    )
  }

  private fun simpleDetector(): ObjectGrowthDetector {
    val referenceMatchers = JdkReferenceMatchers.defaults + HeapTraversalOutput.ignoredReferences
    val referenceReaderFactory = ActualMatchingReferenceReaderFactory(referenceMatchers)
    val gcRootProvider = MatchingGcRootProvider(referenceMatchers)
    return ObjectGrowthDetector(gcRootProvider, referenceReaderFactory)
  }

  private fun openJdkDetector(): ObjectGrowthDetector {
    val referenceMatchers = JdkReferenceMatchers.defaults + HeapTraversalOutput.ignoredReferences

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
    return ObjectGrowthDetector(gcRootProvider, referenceReaderFactory)
  }

  private fun dumpHeapGraph(): CloseableHeapGraph {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "${System.nanoTime()}.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile.openHeapGraph()
  }
}
