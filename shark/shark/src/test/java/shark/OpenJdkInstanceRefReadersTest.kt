package shark

import java.io.File
import java.lang.ref.ReferenceQueue
import java.util.LinkedList
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
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
  class Holder(val retained: Retained)
  class Owner(val holder: Holder)

  companion object {
    @JvmStatic
    var leakRoot: Any? = null

    @JvmStatic
    var strongOwner: Owner? = null

    val NO_EXPANDER = OptionalFactory { null }

    /**
     * Maps [value] to a key that no longer has a holder once this returns, so that the next GC
     * clears it. In the calling method the key would stay in a local slot of a frame that's still
     * on the stack.
     */
    private fun putWithKeyGoingOutOfScope(
      map: WeakHashMap<Any, Any>,
      value: Any
    ) {
      map[SomeKey()] = value
    }

    /**
     * Clears every weakly reachable referent, which for a [WeakHashMap] means its stale keys. The
     * entries that held them stay in the table: only an operation on the map expunges those, and
     * not calling one is the point of the tests that call this.
     */
    private fun clearWeakKeys() {
      System.gc()
    }
  }

  @get:Rule
  val testFolder = TemporaryFolder()

  @After fun tearDown() {
    leakRoot = null
    strongOwner = null
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

  @Test fun `LinkedList with null element expanded`() {
    val list = LinkedList<Any?>()
    list += null
    list += Retained()
    leakRoot = list

    val refPath = findLeak(LINKED_LIST)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(LinkedList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[1]")
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

  @Test fun `ArrayList with null element expanded`() {
    val list = ArrayList<Any?>()
    list += null
    list += Retained()
    leakRoot = list

    val refPath = findLeak(OpenJdkInstanceRefReaders.ARRAY_LIST)

    assertThat(refPath).hasSize(1)

    with(refPath.first()) {
      assertThat(owningClassName).isEqualTo(ArrayList::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("[1]")
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

  @Test fun `HashMap with null value expanded`() {
    val map = HashMap<Any, Any?>()
    map[SomeKey()] = null
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

    val hprofFile = dumpHeap()
    val refPath = hprofFile.findPathFromLeak(
      computeRetainedHeapSize = false,
      virtualRefReaderFactory = NO_EXPANDER
    )

    assertThat(refPath).hasSize(3)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(ConcurrentHashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.concurrent.ConcurrentHashMap\$Node[]")
      assertThat(referenceDisplayName).isEqualTo(
        hprofFile.singleTableEntryName(ConcurrentHashMap::class to "table")
      )
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

    val hprofFile = dumpHeap()
    val refPath = hprofFile.findPathFromLeak(
      computeRetainedHeapSize = false,
      virtualRefReaderFactory = NO_EXPANDER
    )

    assertThat(refPath).hasSize(3)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(HashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.HashMap\$Node[]")
      assertThat(referenceDisplayName).isEqualTo(
        hprofFile.singleTableEntryName(HashMap::class to "table")
      )
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
        "\\[instance @\\d* \\(0x[0-9a-f]+\\) of shark\\.OpenJdkInstanceRefReadersTest\\\$SomeKey\\]"
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

    val hprofFile = dumpHeap()
    val refPath = hprofFile.findPathFromLeak(
      computeRetainedHeapSize = false,
      virtualRefReaderFactory = NO_EXPANDER
    )

    assertThat(refPath).hasSize(3)

    with(refPath[0]) {
      assertThat(owningClassName).isEqualTo(WeakHashMap::class.qualifiedName)
      assertThat(referenceDisplayName).isEqualTo("table")
    }
    with(refPath[1]) {
      assertThat(owningClassName).isEqualTo("java.util.WeakHashMap\$Entry[]")
      assertThat(referenceDisplayName).isEqualTo(
        hprofFile.singleTableEntryName(WeakHashMap::class to "table")
      )
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
      assertThat(referenceDisplayName).matches(
        "\\[instance @\\d* \\(0x[0-9a-f]+\\) of shark\\.OpenJdkInstanceRefReadersTest\\\$SomeKey\\]"
      )
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

  @Test fun `WeakHashMap with cleared key surfaces the value and keeps the entry internal`() {
    val map = WeakHashMap<Any, Any>()
    leakRoot = map
    putWithKeyGoingOutOfScope(map, Retained())

    clearWeakKeys()

    dumpHeap().openHeapGraph().use { graph ->
      val mapInstance = graph.findClassByName(OpenJdkInstanceRefReadersTest::class.java.name)!![
        ::leakRoot.name
      ]!!.valueAsInstance!!
      val entry = mapInstance["java.util.WeakHashMap", "table"]!!
        .valueAsObjectArray!!
        .readElements()
        .single { it.isNonNullReference }
        .asObject!!.asInstance!!
      // The premise of the test: the key is gone and the value isn't.
      assertThat(entry["java.lang.ref.Reference", "referent"]!!.value.isNullReference).isTrue()
      val entryValueObjectId = entry["java.util.WeakHashMap\$Entry", "value"]!!.value.asObjectId!!

      val references = FlatteningPartitionedInstanceReferenceReader(
        graph, FieldInstanceReferenceReader(graph, JdkReferenceMatchers.defaults)
      ).read(
        OpenJdkInstanceRefReaders.WEAK_HASH_MAP.create(graph)!!,
        mapInstance
      ).toList()

      val details = references.map { it.lazyDetailsResolver.resolve() }

      // The cut set comes first, and the entry is part of it.
      assertThat(references.first().valueObjectId).isEqualTo(entryValueObjectId)
      assertThat(references.first().isLowPriority).isTrue()
      assertThat(details.first().name).isEqualTo("cleared key, removed on next map access")

      // Which is what keeps the traversal of the internals from having to follow the entry's own
      // value field, and from surfacing everything below it as a direct child of the map.
      assertThat(
        details.map { graph.findObjectById(it.locationClassObjectId).asClass!!.name to it.name }
      ).doesNotContain("java.util.WeakHashMap\$Entry" to "value")
      assertThat(references.filter { it.valueObjectId == entryValueObjectId }).hasSize(1)
    }
  }

  @Test fun `WeakHashMap with cleared key doesn't take over the path to its value`() {
    val retained = Retained()
    // Two references deep, so that the path through the map is the shorter of the two and only
    // the entry being low priority can keep it from being the one reported.
    strongOwner = Owner(Holder(retained))
    val map = WeakHashMap<Any, Any>()
    leakRoot = map
    putWithKeyGoingOutOfScope(map, retained)

    clearWeakKeys()

    val leakTrace = dumpHeap().traceLeak(
      computeRetainedHeapSize = false,
      virtualRefReaderFactory = OpenJdkInstanceRefReaders.WEAK_HASH_MAP,
      flattening = true
    )!!

    // The value is held by the map and by Owner. An entry waiting to be expunged is the less
    // interesting of the two, so the trace goes through the holder an application can fix.
    assertThat(leakTrace.referencePath.map { it.referenceDisplayName })
      .describedAs(leakTrace.toSimplePathString())
      .containsSequence(::strongOwner.name, Owner::holder.name, Holder::retained.name)
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

    val hprofFile = dumpHeap()
    val refPath = hprofFile.findPathFromLeak(
      computeRetainedHeapSize = false,
      virtualRefReaderFactory = NO_EXPANDER
    )

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
      assertThat(referenceDisplayName).isEqualTo(
        hprofFile.singleTableEntryName(HashSet::class to "map", HashMap::class to "table")
      )
    }
    with(refPath[3]) {
      assertThat(owningClassName).isEqualTo("java.util.HashMap\$Node")
      assertThat(referenceDisplayName).isEqualTo("key")
    }
  }

  /**
   * The display name a leak trace should give the reference from a hash table array to the single
   * entry it holds, i.e. `[index]` where index is the position of that entry in the array. The
   * array is the one reached by following [fieldPath] from [leakRoot].
   *
   * Which bucket an entry lands in comes from the identity hash of its key, since [SomeKey] and
   * [Retained] don't override [Any.hashCode], so it changes from one JVM run to the next and can't
   * be asserted as a literal.
   */
  private fun File.singleTableEntryName(
    vararg fieldPath: Pair<KClass<out Any>, String>
  ): String {
    val entryIndex = openHeapGraph().use { graph ->
      val testClass = graph.findClassByName(OpenJdkInstanceRefReadersTest::class.java.name)!!
      var field = testClass[::leakRoot.name]!!
      fieldPath.forEach { (declaringClass, fieldName) ->
        field = field.valueAsInstance!![declaringClass, fieldName]!!
      }
      val elementIds = field.valueAsObjectArray!!.readRecord().elementIds
      elementIds.indices.single { elementIds[it] != ValueHolder.NULL_REFERENCE }
    }
    return "[$entryIndex]"
  }

  private fun findLeak(
    expanderFactory: OptionalFactory,
    flattening: Boolean = false
  ): List<LeakTraceReference> {
    val hprofFile = dumpHeap()
    return hprofFile.findPathFromLeak(
      computeRetainedHeapSize = false,
      virtualRefReaderFactory = expanderFactory,
      flattening = flattening
    )
  }

  private fun File.findPathFromLeak(
    computeRetainedHeapSize: Boolean,
    virtualRefReaderFactory: OptionalFactory,
    flattening: Boolean = false,
  ): List<LeakTraceReference> {
    val leakTrace = traceLeak(
      computeRetainedHeapSize = computeRetainedHeapSize,
      virtualRefReaderFactory = virtualRefReaderFactory,
      flattening = flattening
    ) ?: return emptyList()
    val index = leakTrace.referencePath.indexOfFirst { it.referenceName == ::leakRoot.name }
    // Returning the whole path when leakRoot isn't on it would silently assert about a path
    // through something else entirely, which is a confusing way to find out the heap held what
    // the test is about in more than one place.
    check(index != -1) {
      "Expected the path to go through ${::leakRoot.name}:\n${leakTrace.toSimplePathString()}"
    }
    val refFromExpandedTypeIndex = index + 1
    return leakTrace.referencePath.subList(refFromExpandedTypeIndex, leakTrace.referencePath.size)
  }

  private fun File.traceLeak(
    computeRetainedHeapSize: Boolean,
    virtualRefReaderFactory: OptionalFactory,
    flattening: Boolean,
  ): LeakTrace? {
    val leaks = openHeapGraph().use { graph ->
      val referenceMatchers = JdkReferenceMatchers.defaults

      val virtualRefReaders = virtualRefReaderFactory.create(graph)?.let {
        listOf(it)
      } ?: emptyList()

      val fieldRefReader = FieldInstanceReferenceReader(graph, referenceMatchers)
      val instanceExpander = ChainingInstanceReferenceReader(
        virtualRefReaders = virtualRefReaders + JavaLocalReferenceReader(graph, referenceMatchers),
        flatteningInstanceReader = if (flattening) {
          FlatteningPartitionedInstanceReferenceReader(graph, fieldRefReader)
        } else {
          null
        },
        fieldRefReader = fieldRefReader
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
          objectSizeCalculatorFactory = if (computeRetainedHeapSize) {
            ObjectSizeCalculator.Factory { heapGraph ->
              ObjectSizeCalculator { heapGraph.findObjectById(it).recordSize }
            }
          } else {
            null
          },
        ),
        objectInspectors = emptyList(),
        listener = {}
      ).createFor(graph)

      tracer.traceObjects(objectIds).apply {
        println(this)
      }
    }
    val firstApplicationLeak = leaks.applicationLeaks.firstOrNull() ?: return null
    return firstApplicationLeak.leakTraces.first().apply {
      println(toSimplePathString())
    }
  }

  private fun dumpHeap(): File {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    return hprofFile
  }
}
