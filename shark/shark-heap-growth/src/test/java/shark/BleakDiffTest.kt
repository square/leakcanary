package shark

import java.io.File
import java.util.LinkedList
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph

class BleakDiffTest {

  class Thing
  class CustomLinkedList(var next: CustomLinkedList? = null)

  @get:Rule
  val testFolder = TemporaryFolder()

  // TODO Investigate if strings are skipped / something funky native might be going on.

  val leaky = mutableListOf<Thing>()
  val leakyLinkedList = LinkedList<Thing>()
  val leakyHashMap = HashMap<String, Thing>()
  val leakyLinkedHashMap = LinkedHashMap<String, Thing>()
  var customLeakyLinkedList = CustomLinkedList()

  @Test
  fun bleakDiffPlayground() {
    val heapDumper = ScenarioLoopHeapDumper(
      maxHeapDumps = 10,
      heapGraphProvider = this::dumpHeapGraph,
      scenarioLoopsPerDump = 1
    )

    heapDumper.assertNoHeapGrowth {
      leaky += Thing()
      leakyLinkedList += Thing()
      leakyHashMap[UUID.randomUUID().toString()] = Thing()
      leakyLinkedHashMap[UUID.randomUUID().toString()] = Thing()
      customLeakyLinkedList = CustomLinkedList(customLeakyLinkedList)
    }
  }

  private fun dumpHeapGraph(): CloseableHeapGraph {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "${System.nanoTime()}.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile.openHeapGraph()
  }
}
