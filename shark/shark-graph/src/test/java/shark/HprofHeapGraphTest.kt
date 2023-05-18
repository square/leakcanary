package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.StickyClass
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord

class HprofHeapGraphTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test
  fun `non system class can be found`() {
    val className = "com.example.SimpleClass"

    HprofWriter.openWriterFor(hprofFile).use { writer ->
      val classNameRecord = StringRecord(1L, className)
      writer.write(classNameRecord)
      writer.writeClass(42, classNameRecord, rootClass = false)
    }

    hprofFile.openHeapGraph().use { graph ->
      assertThat(graph.findClassByName(className)).isNotNull
    }
  }

  @Test
  fun `system class can be found`() {
    val className = "com.example.SimpleClass"

    HprofWriter.openWriterFor(hprofFile).use { writer ->
      val classNameRecord = StringRecord(1L, className)
      writer.write(classNameRecord)
      writer.writeClass(42, classNameRecord, rootClass = true)
    }

    hprofFile.openHeapGraph().use { graph ->
      assertThat(graph.findClassByName(className)).isNotNull
    }
  }

  @Test
  fun `system class prioritized over non system class`() {
    val className = "com.example.SimpleClass"

    HprofWriter.openWriterFor(hprofFile).use { writer ->
      val classNameRecord = StringRecord(1L, className)
      writer.write(classNameRecord)
      writer.writeClass(24, classNameRecord, rootClass = false)
      writer.writeClass(25, classNameRecord, rootClass = false)
      writer.writeClass(42, classNameRecord, rootClass = true)
      writer.writeClass(43, classNameRecord, rootClass = false)
    }

    hprofFile.openHeapGraph().use { graph ->
      val heapClass = graph.findClassByName(className)!!
      assertThat(heapClass.objectId).isEqualTo(42)
    }
  }

  private fun HprofWriter.writeClass(
    classId: Long,
    classNameRecord: StringRecord,
    rootClass: Boolean
  ) {
    val loadClass = LoadClassRecord(1, classId, 1, classNameRecord.id)
    val classDump = ClassDumpRecord(
      id = loadClass.id,
      stackTraceSerialNumber = 1,
      superclassId = 0,
      classLoaderId = 0,
      signersId = 0,
      protectionDomainId = 0,
      instanceSize = 0,
      staticFields = emptyList(),
      fields = emptyList()
    )
    write(loadClass)
    if (rootClass) {
      write(GcRootRecord(gcRoot = StickyClass(classId)))
    }
    write(classDump)
  }
}
