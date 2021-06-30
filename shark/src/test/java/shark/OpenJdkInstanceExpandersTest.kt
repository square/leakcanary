package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import java.io.File
import java.util.LinkedList

class OpenJdkInstanceExpandersTest {

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

  @Test fun `LinkedList expanded`() {
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

  @Test fun `LinkedList no expander`() {
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

  // TODO Support Vector, ArrayList, HashSet

  class SomeKey

  @Test fun `HashMap expanded`() {
    val map = HashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceExpanders.HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(HashMap::class.qualifiedName)
    }
  }

  @Test fun `LinkedHashMap expanded`() {
    val map = LinkedHashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceExpanders.HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(LinkedHashMap::class.qualifiedName)
    }
  }

  @Test fun `HashMap no expander`() {
    val map = HashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak { null }

    assertThat(refPath).hasSize(3)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(HashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.HashMap\$Node[]")
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
    with(refPath[2]) {
      assertThat(owningClassName).isEqualTo("java.util.HashMap\$Node")
      assertThat(referenceDisplayName).isEqualTo("value")
    }
  }

  @Test fun `HashMap expanded with non string key`() {
    val map = HashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceExpanders.HASH_MAP)

    with(refPath.first()) {
      assertThat(referenceDisplayName).matches("\\[instance @\\d* of shark\\.OpenJdkInstanceExpandersTest\\\$SomeKey]")
    }
  }

  @Test fun `HashMap expanded with string key`() {
    val map = HashMap<Any, Any>()
    map["StringKey"] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceExpanders.HASH_MAP)

    with(refPath.first()) {
      assertThat(referenceDisplayName).isEqualTo("[StringKey]")
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
    println(leakTrace.toSimplePathString())
    val index = leakTrace.referencePath.indexOfFirst { it.referenceName == ::leakRoot.name }
    val refFromExpandedTypeIndex = index + 1
    return leakTrace.referencePath.subList(refFromExpandedTypeIndex, leakTrace.referencePath.size)
  }
}