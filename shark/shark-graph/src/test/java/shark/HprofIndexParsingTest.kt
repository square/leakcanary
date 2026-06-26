package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.GcRoot.StickyClass
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.internal.HprofInMemoryIndex

class HprofIndexParsingTest {

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  @Test fun `duplicated StickyClass GC roots are deduplicated`() {
    val className = StringRecord(id, "com.example.VeryStickyClass")
    val loadClassRecord = LoadClassRecord(1, id, 1, className.id)
    val classDump = ClassDumpRecord(
      id = loadClassRecord.id,
      stackTraceSerialNumber = 1,
      superclassId = 0,
      classLoaderId = 0,
      signersId = 0,
      protectionDomainId = 0,
      instanceSize = 0,
      staticFields = emptyList(),
      fields = emptyList()
    )
    val stickyClassRecords = (1..5).map { GcRootRecord(StickyClass(loadClassRecord.id)) }
    val bytes = (listOf(className, loadClassRecord, classDump) + stickyClassRecords).asHprofBytes()

    val stickyClassRoots = bytes.openHeapGraph().use { graph: HeapGraph ->
      graph.gcRoots.filterIsInstance(StickyClass::class.java)
    }

    assertThat(stickyClassRoots).hasSize(1)
    assertThat(stickyClassRoots.first().id).isEqualTo(loadClassRecord.id)
  }

  @Test fun `heap dump index is computed based on position in heap dump`() {
    val bytes = dump {
      instance(clazz("com.example.MyClass1"))
      instance(clazz("com.example.MyClass2"))
    }

    bytes.openHeapGraph().use { graph ->
      val class1 = graph.findClassByName("com.example.MyClass1")!!
      val class1Index = graph.findHeapDumpIndex(class1.objectId)
      val instance1 = class1.instances.single()
      val instance1Index = graph.findHeapDumpIndex(instance1.objectId)

      val class2 = graph.findClassByName("com.example.MyClass2")!!
      val class2Index = graph.findHeapDumpIndex(class2.objectId)
      val instance2 = class2.instances.single()
      val instance2Index = graph.findHeapDumpIndex(instance2.objectId)

      assertThat(instance1Index).isEqualTo(class1Index + 1)
      assertThat(class2Index).isEqualTo(instance1Index + 1)
      assertThat(instance2Index).isEqualTo(class2Index + 1)
    }
  }

  @Test fun `indexedObjectOrNull index round trips through objectAtIndex for primitive arrays`() {
    // The global index returned by indexedObjectOrNull is the concatenation of class, instance,
    // object array and primitive array indexes. The offset for primitive arrays must add the
    // object array index size, which is only correct when objectArrayIndex.size and
    // primitiveArrayIndex.size differ. Here we have 2 object arrays but 1 primitive array.
    var primitiveArrayId = 0L
    val source = dump {
      val arrayClass = clazz("char[]")
      objectArrayOf(arrayClass, instance(clazz("com.example.MyClass1")))
      objectArrayOf(arrayClass, instance(clazz("com.example.MyClass2")))
      primitiveArrayId = primitiveLongArray(longArrayOf(1L, 2L, 3L))
    }

    val header = source.openStreamingSource().use { HprofHeader.parseHeaderOf(it) }
    val reader = StreamingHprofReader.readerFor(source, header)
    val index = HprofInMemoryIndex.indexHprof(
      reader = reader,
      hprofHeader = header,
      proguardMapping = null,
      indexedGcRootTags = HprofIndex.defaultIndexedGcRootTags()
    )

    val globalIndex = index.indexedObjectOrNull(primitiveArrayId)!!.first
    val (roundTrippedId, _) = index.objectAtIndex(globalIndex)

    assertThat(roundTrippedId).isEqualTo(primitiveArrayId)
  }
}
