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
import shark.internal.HprofInMemoryIndex

class HprofHeapGraphEdgeCasesTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test
  fun `When duplicating LoadClassRecord, HprofHeapGraph selects the class that has a ClassDumpRecord`() {
    val objectClassIds = listOf<Long>(1, 2, 3)
    val loadedClassId = 2L
    HprofWriter.openWriterFor(
      hprofFile = hprofFile,
      hprofHeader = HprofHeader(
        version = HprofVersion.JDK_6,
        identifierByteSize = 8
      )
    ).use { writer ->
      val objectClassNameRecord = StringRecord(1L, "java/lang/Object")
      writer.write(objectClassNameRecord)
      for (classId in objectClassIds) {
        writer.write(
          LoadClassRecord(
            classSerialNumber = 1,
            id = classId,
            stackTraceSerialNumber = 1,
            classNameStringId = objectClassNameRecord.id
          )
        )
        writer.write(GcRootRecord(gcRoot = StickyClass(classId)))
      }
      writer.write(
        ClassDumpRecord(
          id = loadedClassId,
          stackTraceSerialNumber = 1,
          superclassId = 0,
          classLoaderId = 0,
          signersId = 0,
          protectionDomainId = 0,
          instanceSize = 0,
          staticFields = emptyList(),
          fields = emptyList()
        )
      )
    }

    hprofFile.openHeapGraph().use { graph ->
      val objectClass = graph.findClassByName("java.lang.Object")!!
      assertThat(objectClass.objectId).isEqualTo(loadedClassId)
    }
  }

  @Test
  fun `When duplicating LoadClassRecord, HprofInMemoryIndex#indexedClassSequence() contains only the class that has a ClassDumpRecord`() {
    val objectClassIds = listOf<Long>(1, 2, 3)
    val loadedClassId = 2L

    HprofWriter.openWriterFor(
      hprofFile,
      HprofHeader(version = HprofVersion.JDK_6, identifierByteSize = 8)
    ).use { writer ->
      val objectClassNameRecord = StringRecord(1L, "java/lang/Object")
      writer.write(objectClassNameRecord)
      for (classId in objectClassIds) {
        writer.write(
          LoadClassRecord(
            classSerialNumber = 1,
            id = classId,
            stackTraceSerialNumber = 1,
            classNameStringId = objectClassNameRecord.id
          )
        )
        writer.write(GcRootRecord(gcRoot = StickyClass(classId)))
      }
      writer.write(
        ClassDumpRecord(
          id = loadedClassId,
          stackTraceSerialNumber = 1,
          superclassId = 0,
          classLoaderId = 0,
          signersId = 0,
          protectionDomainId = 0,
          instanceSize = 0,
          staticFields = emptyList(),
          fields = emptyList()
        )
      )
    }

    val source = FileSourceProvider(hprofFile)
    val header = source.openStreamingSource().use { HprofHeader.parseHeaderOf(it) }
    val reader = StreamingHprofReader.readerFor(source, header)
    val index = HprofInMemoryIndex.indexHprof(
      reader = reader,
      hprofHeader = header,
      proguardMapping = null,
      indexedGcRootTags = HprofIndex.defaultIndexedGcRootTags()
    )

    val objectClasses = index.indexedClassSequence()
      .filter { (_, indexedClass) ->
        indexedClass.superclassId == ValueHolder.NULL_REFERENCE
      }
      .toList()

    assertThat(objectClasses).hasSize(1)
    assertThat(objectClasses.single().first).isEqualTo(loadedClassId)
  }

  @Test
  fun `When duplicating LoadClassRecord, HprofInMemoryIndex#classId() returns the class that has a ClassDumpRecord`() {
    val objectClassIds = listOf<Long>(1, 2, 3)
    val loadedClassId = 2L

    HprofWriter.openWriterFor(
      hprofFile,
      HprofHeader(version = HprofVersion.JDK_6, identifierByteSize = 8)
    ).use { writer ->
      val objectClassNameRecord = StringRecord(1L, "java/lang/Object")
      writer.write(objectClassNameRecord)
      for (classId in objectClassIds) {
        writer.write(
          LoadClassRecord(
            classSerialNumber = 1,
            id = classId,
            stackTraceSerialNumber = 1,
            classNameStringId = objectClassNameRecord.id
          )
        )
        writer.write(GcRootRecord(gcRoot = StickyClass(classId)))
      }
      writer.write(
        ClassDumpRecord(
          id = loadedClassId,
          stackTraceSerialNumber = 1,
          superclassId = 0,
          classLoaderId = 0,
          signersId = 0,
          protectionDomainId = 0,
          instanceSize = 0,
          staticFields = emptyList(),
          fields = emptyList()
        )
      )
    }

    val source = FileSourceProvider(hprofFile)
    val header = source.openStreamingSource().use { HprofHeader.parseHeaderOf(it) }
    val reader = StreamingHprofReader.readerFor(source, header)
    val index = HprofInMemoryIndex.indexHprof(
      reader = reader,
      hprofHeader = header,
      proguardMapping = null,
      indexedGcRootTags = HprofIndex.defaultIndexedGcRootTags()
    )

    assertThat(index.classId("java.lang.Object")).isEqualTo(loadedClassId)
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
