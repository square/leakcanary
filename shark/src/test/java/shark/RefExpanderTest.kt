package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import java.io.File
import java.util.LinkedList

class RefExpanderTest {

  class Retained

  companion object {
    @JvmStatic
    var leakRoot: Any? = null
  }

  @get:Rule
  val testFolder = TemporaryFolder()

  @After fun tearDown() {
    leakRoot = null
  }

  @Test fun `OpenJdk LinkedList expanded`() {
    val list = LinkedList<Any>()
    list += Retained()
    leakRoot = list

    val refPath = findLeak(OpenJdkInstanceExpanders.LINKED_LIST)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(LinkedList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
  }

  @Test fun `OpenJdk LinkedList no expander`() {
    val list = LinkedList<Any>()
    list += Retained()
    leakRoot = list

    val refPath = findLeak { null }

    assertThat(refPath).hasSize(2)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(LinkedList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("first")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.LinkedList\$Node")
      assertThat(referenceDisplayName).isEqualTo("item")
    }
  }

  private fun findLeak(expanderFactory: (HeapGraph) -> MatchingInstanceExpander?): List<LeakTraceReference> {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)

    val instanceExpander = MatchingChainedInstanceExpander.factory(listOf(expanderFactory))
    val analysis = hprofFile.checkForLeaks<HeapAnalysisSuccess>(
      leakFilters = listOf(object :
        LeakingObjectFilter {
        override fun isLeakingObject(heapObject: HeapObject): Boolean {
          return heapObject.asInstance?.instanceOf(Retained::class) ?: false
        }
      }),
      instanceExpander = instanceExpander
    )
    val leakTrace = analysis.applicationLeaks[0].leakTraces.first()
    val index = leakTrace.referencePath.indexOfFirst { it.referenceName == ::leakRoot.name }
    val refFromExpandedTypeIndex = index + 1
    return leakTrace.referencePath.subList(refFromExpandedTypeIndex, leakTrace.referencePath.size)
  }
}