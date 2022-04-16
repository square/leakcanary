package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.GcRoot.StickyClass
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord

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
}
