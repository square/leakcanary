package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ActualMatchingReferenceReaderFactory
import shark.JvmObjectGrowthReferenceMatchers
import shark.MatchingGcRootProvider
import shark.ObjectGrowthDetector
import shark.forJvmHeap

class JvmLiveObjectGrowthDetectorTest {

  class MultiLeaky {
    val leaky = Any() to Any()
  }

  class CustomLinkedList(var next: CustomLinkedList? = null)

  @get:Rule
  val testFolder = TemporaryFolder()

  val leakies = mutableListOf<Any>()

  val stringLeaks = mutableListOf<String>()

  var customLeakyLinkedList = CustomLinkedList()

  val leakyHashMap = HashMap<String, Any>()

  val multiLeakies = mutableListOf<MultiLeaky>()

  @Test
  fun `empty scenario leads to no heap growth`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    val emptyScenario = {}

    val heapTraversal = detector.findRepeatedlyGrowingObjects(roundTripScenario = emptyScenario)

    assertThat(heapTraversal.growingObjects).isEmpty()
  }

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      leakies += Any()
    }

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `string leak increase leads to heap growth`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      stringLeaks += "Yo ${++index}"
    }

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `leak increase that ends leads to no heap growth`() {
    val maxHeapDumps = 10
    val stopLeakingIndex = maxHeapDumps / 2
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(
        maxHeapDumps = maxHeapDumps,
        scenarioLoopsPerDump = 1
      )

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      if (++index < stopLeakingIndex) {
        leakies += Any()
      }
    }

    assertThat(heapTraversal.growingObjects).isEmpty()
  }

  @Test
  fun `multiple leaky scenarios per dump leads to heap growth`() {
    val scenarioLoopsPerDump = 5
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = scenarioLoopsPerDump)

    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      leakies += Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    val growingChild = growingObject.growingChildren.single()
    assertThat(growingChild.objectCountIncrease).isEqualTo(scenarioLoopsPerDump)
  }

  @Test
  fun `detect growth of custom linked list`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `custom leaky linked list reports descendant to root as flattened collection`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }

    val growingObject = heapTraversal.growingObjects.single()
    val growingChild = growingObject.growingChildren.single()
    assertThat(growingChild.objectCountIncrease).isEqualTo(4)
  }

  @Test
  fun `growth along shared sub paths reported as single growth of shortest path`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      multiLeakies += MultiLeaky()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name).contains("ArrayList")
  }

  @Test
  fun `OpenJdk HashMap without synthetic refs shows internal table array growing`() {
    val detector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      leakyHashMap["key${++index}"] = Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name).startsWith("INSTANCE_FIELD HashMap.table")
  }

  @Test
  fun `OpenJdk HashMap with synthetic refs shows itself growing`() {
    val detector = ObjectGrowthDetector.forJvmHeap()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      leakyHashMap["key${++index}"] = Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name)
      .startsWith("INSTANCE_FIELD JvmLiveObjectGrowthDetectorTest.leakyHashMap")
  }

  @Test
  fun `OpenJdk ArrayList virtualized as array`() {
    val detector = ObjectGrowthDetector.forJvmHeap()
      .repeatingJvmInProcessScenario(scenarioLoopsPerDump = 1)

    val heapTraversal = detector.findRepeatedlyGrowingObjects {
      leakies += Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name).contains("leakies")
  }

  private fun ObjectGrowthDetector.Companion.forJvmHeapNoSyntheticRefs(): ObjectGrowthDetector {
    val referenceMatchers = JvmObjectGrowthReferenceMatchers.defaults
    return ObjectGrowthDetector(
      gcRootProvider = MatchingGcRootProvider(JvmObjectGrowthReferenceMatchers.defaults),
      referenceReaderFactory = ActualMatchingReferenceReaderFactory(referenceMatchers)
    )
  }
}
