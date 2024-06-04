package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ActualMatchingReferenceReaderFactory
import shark.HeapDiff
import shark.JvmObjectGrowthReferenceMatchers
import shark.MatchingGcRootProvider
import shark.ObjectGrowthDetector
import shark.forJvmHeap

class JvmLiveObjectGrowthDetectorTest {

  class MultiLeaky {
    val leaky = Any() to Any()
  }

  class CustomLinkedList(var next: CustomLinkedList? = null)

  val leakies = mutableListOf<Any>()

  val stringLeaks = mutableListOf<String>()

  var customLeakyLinkedList = CustomLinkedList()

  val leakyHashMap = HashMap<String, Any>()

  val multiLeakies = mutableListOf<MultiLeaky>()

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `empty scenario leads to no heap growth`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    val emptyScenario = {}

    val heapTraversal = detector.findRepeatedlyGrowingObjects(
      scenarioLoopsPerDump = 1,
      roundTripScenario = emptyScenario
    )

    assertThat(heapTraversal.growingObjects).isEmpty()
  }

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      leakies += Any()
    }

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `string leak increase leads to heap growth`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      stringLeaks += "Yo ${++index}"
    }

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `leak increase that ends leads to no heap growth`() {
    val maxHeapDumps = 10
    val stopLeakingIndex = maxHeapDumps / 2
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = maxHeapDumps,
      scenarioLoopsPerDump = 1
    ) {
      if (++index < stopLeakingIndex) {
        leakies += Any()
      }
    }

    assertThat(heapTraversal.growingObjects).isEmpty()
  }

  @Test
  fun `multiple leaky scenarios per dump leads to heap growth`() {
    val scenarioLoopsPerDump = 5
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    val heapTraversal =
      detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = scenarioLoopsPerDump) {
        leakies += Any()
      }

    val growingObject = heapTraversal.growingObjects.single()
    val growingChild = growingObject.growingChildren.single()
    assertThat(growingChild.objectCountIncrease).isEqualTo(scenarioLoopsPerDump)
  }

  @Test
  fun `detect growth of custom linked list`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }

    assertThat(heapTraversal.growingObjects).hasSize(1)
  }

  @Test
  fun `custom leaky linked list reports descendant to root as flattened collection`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
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
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      multiLeakies += MultiLeaky()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name).contains("ArrayList")
  }

  @Test
  fun `OpenJdk HashMap without synthetic refs shows internal table array growing`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
    )

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      leakyHashMap["key${++index}"] = Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name).startsWith("INSTANCE_FIELD HashMap.table")
  }

  @Test
  fun `OpenJdk HashMap with synthetic refs shows itself growing`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeap(),
    )

    var index = 0
    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      leakyHashMap["key${++index}"] = Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name)
      .startsWith("INSTANCE_FIELD JvmLiveObjectGrowthDetectorTest.leakyHashMap")
  }

  @Test
  fun `OpenJdk ArrayList virtualized as array`() {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeap(),
    )

    val heapTraversal = detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      leakies += Any()
    }

    val growingObject = heapTraversal.growingObjects.single()
    assertThat(growingObject.name).contains("leakies")
  }

  @Test
  fun `DeleteOnHeapDumpClose invokes file deletion in between each scenario`() {
    // Nothing to delete on first run.
    var didDeleteFile = true
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.DeleteOnHeapDumpClose {
        it.delete()
        didDeleteFile = true
      }
    )

    detector.findRepeatedlyGrowingObjects(scenarioLoopsPerDump = 1) {
      assertThat(didDeleteFile).isTrue()
      didDeleteFile = false
      leakies += Any()
    }

    assertThat(didDeleteFile).isTrue()
  }

  @Test
  fun `KeepHeapDumps does not delete heap dumps`() {
    val heapDumpDirectory = tempFolder.newFolder()
    val maxHeapDump = 5
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumps,
      heapDumpDirectoryProvider = { heapDumpDirectory }
    )

    detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = maxHeapDump,
      scenarioLoopsPerDump = 1
    ) {
      leakies += Any()
    }

    val heapDumpFileCount = heapDumpDirectory.listFiles()!!.count { it.extension == "hprof" }
    assertThat(heapDumpFileCount).isEqualTo(maxHeapDump)
  }

  @Test
  fun `KeepHeapDumpsOnObjectsGrowing does invokes file deletion in between each scenario`() {
    var didDeleteFile = false
    val heapDumpDirectory = tempFolder.newFolder()
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumpsOnObjectsGrowing {
        didDeleteFile = true
        it.delete()
      },
      heapDumpDirectoryProvider = { heapDumpDirectory }
    )

    detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = 5,
      scenarioLoopsPerDump = 1
    ) {
      assertThat(didDeleteFile).isFalse()
      leakies += Any()
    }
  }

  @Test
  fun `KeepHeapDumpsOnObjectsGrowing does not delete any heap dump if objects growing`() {
    val maxHeapDump = 5
    val heapDumpDirectory = tempFolder.newFolder()
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumpsOnObjectsGrowing(),
      heapDumpDirectoryProvider = { heapDumpDirectory }
    )

    detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = maxHeapDump,
      scenarioLoopsPerDump = 1
    ) {
      leakies += Any()
    }

    val heapDumpFileCount = heapDumpDirectory.listFiles()!!.count { it.extension == "hprof" }
    assertThat(heapDumpFileCount).isEqualTo(maxHeapDump)
  }

  @Test
  fun `KeepHeapDumpsOnObjectsGrowing invokes file deletion on completion if objects not growing`() {
    var filesDeleted = 0
    val heapDumpDirectory = tempFolder.newFolder()
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepHeapDumpsOnObjectsGrowing {
        filesDeleted++
        it.delete()
      },
      heapDumpDirectoryProvider = { heapDumpDirectory }
    )
    val leakyScenarioRuns = 3

    var i = 1
    detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = 5,
      scenarioLoopsPerDump = 1
    ) {
      assertThat(filesDeleted).isEqualTo(0)
      if (i <= leakyScenarioRuns) {
        leakies += Any()
      }
      i++
    }

    assertThat(filesDeleted).isEqualTo(1 + leakyScenarioRuns)
  }

  @Test
  fun `KeepZippedHeapDumpsOnObjectsGrowing leaves zipped heap dumps if objects growing`() {
    val maxHeapDump = 5
    val heapDumpDirectory = tempFolder.newFolder()
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepZippedHeapDumpsOnObjectsGrowing(),
      heapDumpDirectoryProvider = { heapDumpDirectory }
    )

    detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = maxHeapDump,
      scenarioLoopsPerDump = 1
    ) {
      leakies += Any()
    }

    val heapDumpDirectoryFileExtensions = heapDumpDirectory.listFiles()!!.map { it.extension }
    assertThat(heapDumpDirectoryFileExtensions).hasSize(maxHeapDump)
    assertThat(heapDumpDirectoryFileExtensions).containsOnly("zip")
  }

  @Test
  fun `KeepZippedHeapDumpsOnObjectsGrowing deletes all files if objects not growing`() {
    val heapDumpDirectory = tempFolder.newFolder()
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeapNoSyntheticRefs(),
      heapDumpStorageStrategy = HeapDumpStorageStrategy.KeepZippedHeapDumpsOnObjectsGrowing(),
      heapDumpDirectoryProvider = { heapDumpDirectory }
    )
    val leakyScenarioRuns = 3

    var i = 1
    detector.findRepeatedlyGrowingObjects(
      maxHeapDumps = 5,
      scenarioLoopsPerDump = 1
    ) {
      if (i <= leakyScenarioRuns) {
        leakies += Any()
      }
      i++
    }

    assertThat(heapDumpDirectory.listFiles()).isEmpty()
  }

  private fun ObjectGrowthDetector.Companion.forJvmHeapNoSyntheticRefs(): ObjectGrowthDetector {
    val referenceMatchers = JvmObjectGrowthReferenceMatchers.defaults
    return ObjectGrowthDetector(
      gcRootProvider = MatchingGcRootProvider(JvmObjectGrowthReferenceMatchers.defaults),
      referenceReaderFactory = ActualMatchingReferenceReaderFactory(referenceMatchers)
    )
  }
}
