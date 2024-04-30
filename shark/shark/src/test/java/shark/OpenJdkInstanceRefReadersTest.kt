package shark

import java.io.File
import java.lang.ref.ReferenceQueue
import java.util.LinkedList
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.OpenJdkInstanceRefReaders.LINKED_LIST

class OpenJdkInstanceRefReadersTest {

  class Retained
  class SomeKey

  companion object {
    @JvmStatic
    var leakRoot: Any? = null

    val NO_EXPANDER = OptionalFactory { null }
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

    val refPath = findLeak(LINKED_LIST)

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

    val refPath = findLeak(NO_EXPANDER)

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

  @Test fun `LinkedList retained size is identical whether expanding or not`() {
    val list = LinkedList<Any>()
    list += Retained()
    leakRoot = list

    val hprofFile = dumpHeap()

    val retainedSizeExpanded = hprofFile.findPathFromLeak(
      computeRetainedHeapSize = true,
      virtualRefReaderFactory = LINKED_LIST
    )
      .first()
      .originObject
      .retainedHeapByteSize

    val retainedSizeNotExpanded = hprofFile.findPathFromLeak(
      computeRetainedHeapSize = true,
      virtualRefReaderFactory = { null }
    )
      .first()
      .originObject
      .retainedHeapByteSize

    assertThat(retainedSizeExpanded).isEqualTo(retainedSizeNotExpanded)
  }

  @Test fun `ArrayList expanded`() {
    val list = ArrayList<Any>()
    list += Retained()
    leakRoot = list

    val refPath = findLeak(OpenJdkInstanceRefReaders.ARRAY_LIST)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(ArrayList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
  }

  @Test fun `ArrayList no expander`() {
    val list = ArrayList<Any>()
    list += Retained()
    leakRoot = list

    val refPath = findLeak(NO_EXPANDER)

    assertThat(refPath).hasSize(2)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(ArrayList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("elementData")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.lang.Object[]")
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
  }

  @Test fun `CopyOnWriteArrayList expanded`() {
    val list = CopyOnWriteArrayList<Any>()
    list += Retained()
    leakRoot = list

    val refPath = findLeak(OpenJdkInstanceRefReaders.COPY_ON_WRITE_ARRAY_LIST)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(CopyOnWriteArrayList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
  }

  @Test fun `CopyOnWriteArrayList no expander`() {
    val list = CopyOnWriteArrayList<Any>()
    list += Retained()
    leakRoot = list

    val refPath = findLeak(NO_EXPANDER)

    assertThat(refPath).hasSize(2)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(CopyOnWriteArrayList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("array")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.lang.Object[]")
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
  }

  @Test fun `HashMap expanded`() {
    val map = HashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(HashMap::class.qualifiedName)
    }
  }

  @Test fun `HashMap key expanded`() {
    val map = HashMap<Any, Any>()
    map[Retained()] = "value"
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(HashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[key()]")
    }
  }

  @Test fun `LinkedHashMap expanded`() {
    val map = LinkedHashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(LinkedHashMap::class.qualifiedName)
    }
  }

  @Test fun `ConcurrentHashMap expanded`() {
    val map = ConcurrentHashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceRefReaders.CONCURRENT_HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(ConcurrentHashMap::class.qualifiedName)
    }
  }

  @Test fun `ConcurrentHashMap key expanded`() {
    val map = ConcurrentHashMap<Any, Any>()
    map[Retained()] = "value"
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceRefReaders.CONCURRENT_HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(ConcurrentHashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[key()]")
    }
  }

  @Test fun `ConcurrentHashMap no expander`() {
    val map = ConcurrentHashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(NO_EXPANDER)

    assertThat(refPath).hasSize(3)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(ConcurrentHashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.concurrent.ConcurrentHashMap\$Node[]")
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
    with(refPath[2]) {
      assertThat(owningClassName).isEqualTo("java.util.concurrent.ConcurrentHashMap\$Node")
      assertThat(referenceDisplayName).isEqualTo("val")
    }
  }

  @Test fun `HashMap no expander`() {
    val map = HashMap<Any, Any>()
    map[SomeKey()] = Retained()
    leakRoot = map

    val refPath = findLeak(NO_EXPANDER)

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

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_MAP)

    with(refPath.first()) {
      assertThat(referenceDisplayName).matches(
        "\\[instance @\\d* of shark\\.OpenJdkInstanceRefReadersTest\\\$SomeKey]"
      )
    }
  }

  @Test fun `HashMap expanded with string key`() {
    val map = HashMap<Any, Any>()
    map["StringKey"] = Retained()
    leakRoot = map

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_MAP)

    with(refPath.first()) {
      assertThat(referenceDisplayName).isEqualTo("[\"StringKey\"]")
    }
  }

  @Test fun `WeakHashMap no expander`() {
    val map = WeakHashMap<Any, Any>()
    leakRoot = map
    val retainedKey = SomeKey()
    map[retainedKey] = Retained()

    System.gc()

    val refPath = findLeak(NO_EXPANDER)

    assertThat(refPath).hasSize(3)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(WeakHashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.WeakHashMap\$Entry[]")
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
    with(refPath[2]) {
      assertThat(owningClassName).isEqualTo("java.util.WeakHashMap\$Entry")
      assertThat(referenceDisplayName).isEqualTo("value")
    }
  }

  @Test fun `WeakHashMap expanded`() {
    val map = WeakHashMap<Any, Any>()
    leakRoot = map
    val retainedKey = SomeKey()
    map[retainedKey] = Retained()

    System.gc()

    val refPath = findLeak(OpenJdkInstanceRefReaders.WEAK_HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(WeakHashMap::class.qualifiedName)
      assertThat(referenceDisplayName).matches("\\[instance @\\d* of shark\\.OpenJdkInstanceRefReadersTest\\\$SomeKey]")
    }
  }

  @Test fun `WeakHashMap expanded with string key`() {
    val map = WeakHashMap<Any, Any>()
    leakRoot = map
    val retainedKey = "StringKey"
    map[retainedKey] = Retained()

    System.gc()

    val refPath = findLeak(OpenJdkInstanceRefReaders.WEAK_HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(referenceDisplayName).isEqualTo("[\"StringKey\"]")
    }
  }

  @Test fun `WeakHashMap expanded with null key`() {
    val map = WeakHashMap<Any, Any>()
    leakRoot = map
    map[null] = Retained()

    System.gc()

    val refPath = findLeak(OpenJdkInstanceRefReaders.WEAK_HASH_MAP)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(referenceDisplayName).isEqualTo("[null]")
    }
  }

  @Test fun `HashSet expanded`() {
    val set = HashSet<Any>()
    set += Retained()
    leakRoot = set

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_SET)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(HashSet::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[element()]")
    }
  }

  @Test fun `LinkedHashSet expanded`() {
    val set = LinkedHashSet<Any>()
    set += Retained()
    leakRoot = set

    val refPath = findLeak(OpenJdkInstanceRefReaders.HASH_SET)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(LinkedHashSet::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[element()]")
    }
  }

  @Test fun `HashSet no expander`() {
    val set = HashSet<Any>()
    set += Retained()
    leakRoot = set

    val refPath = findLeak(NO_EXPANDER)

    assertThat(refPath).hasSize(4)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(HashSet::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("map")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo(HashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[2]) {
      assertThat(owningClassName).isEqualTo("java.util.HashMap\$Node[]")
      assertThat(referenceDisplayName).isEqualTo("[0]")
    }
    with(refPath[3]) {
      assertThat(owningClassName).isEqualTo("java.util.HashMap\$Node")
      assertThat(referenceDisplayName).isEqualTo("key")
    }
  }

  private fun findLeak(expanderFactory: OptionalFactory): List<LeakTraceReference> {
    val hprofFile = dumpHeap()
    return hprofFile.findPathFromLeak(computeRetainedHeapSize = false, expanderFactory)
  }

  private fun File.findPathFromLeak(
    computeRetainedHeapSize: Boolean,
    virtualRefReaderFactory: OptionalFactory,
  ): List<LeakTraceReference> {
    val leaks = openHeapGraph().use { graph ->
      val referenceMatchers = JdkReferenceMatchers.defaults

      val virtualRefReaders = virtualRefReaderFactory.create(graph)?.let {
        listOf(it)
      } ?: emptyList()

      val instanceExpander = ChainingInstanceReferenceReader(
        virtualRefReaders = virtualRefReaders + JavaLocalReferenceReader(graph, referenceMatchers),
        flatteningInstanceReader = null,
        fieldRefReader = FieldInstanceReferenceReader(graph, referenceMatchers)
      )

      val referenceReader = DelegatingObjectReferenceReader(
        classReferenceReader = ClassReferenceReader(graph, referenceMatchers),
        instanceReferenceReader = instanceExpander,
        objectArrayReferenceReader = ObjectArrayReferenceReader()
      )

      val leakingObjectFinder = FilteringLeakingObjectFinder(listOf(object :
        LeakingObjectFilter {
        override fun isLeakingObject(heapObject: HeapObject): Boolean {
          return heapObject.asInstance?.instanceOf(Retained::class) ?: false
        }
      }))
      val objectIds = leakingObjectFinder.findLeakingObjectIds(graph)

      val tracer = RealLeakTracerFactory(
        shortestPathFinderFactory = PrioritizingShortestPathFinder.Factory(
          listener = {},
          referenceReaderFactory = { referenceReader },
          gcRootProvider = MatchingGcRootProvider(referenceMatchers),
          computeRetainedHeapSize = computeRetainedHeapSize,
        ),
        objectInspectors = emptyList(),
        listener = {}
      ).createFor(graph)

      tracer.traceObjects(objectIds).apply {
        println(this)
      }
    }
    val firstApplicationLeak = leaks.applicationLeaks.firstOrNull() ?: return emptyList()
    val leakTrace = firstApplicationLeak.leakTraces.first()
    println(leakTrace.toSimplePathString())
    val index = leakTrace.referencePath.indexOfFirst { it.referenceName == ::leakRoot.name }
    val refFromExpandedTypeIndex = index + 1
    return leakTrace.referencePath.subList(refFromExpandedTypeIndex, leakTrace.referencePath.size)
  }

  private fun dumpHeap(): File {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile
  }
}
