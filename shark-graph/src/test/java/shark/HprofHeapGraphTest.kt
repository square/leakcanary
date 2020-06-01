package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.StickyClass
import shark.Hprof.HprofVersion.ANDROID
import shark.Hprof.HprofVersion.JDK_6
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import java.io.File

class HprofHeapGraphTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  lateinit var hprofFile: File

  @Before
  fun setUp() {
    hprofFile = testFolder.newFile("temp.hprof")
  }

  @Test
  fun nonRootClassKeptInJvm() {
    val className = "com.example.SimpleClass"

    HprofWriter.open(hprofFile, hprofVersion = JDK_6)
        .use { writer ->
          val classNameRecord = StringRecord(1L, className)
          writer.write(classNameRecord)
          writer.writeClass(42, classNameRecord, rootClass = false)
        }

    Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          assertThat(graph.findClassByName(className)).isNotNull
        }
  }

  @Test
  fun nonRootClassNotKeptInAndroid() {
    val className = "com.example.SimpleClass"

    HprofWriter.open(hprofFile, hprofVersion = ANDROID)
        .use { writer ->
          val classNameRecord = StringRecord(1L, className)
          writer.write(classNameRecord)
          writer.writeClass(42, classNameRecord, rootClass = false)
        }

    Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          assertThat(graph.findClassByName(className)).isNull()
        }
  }

  @Test
  fun rootClassKeptInAndroid() {
    val className = "com.example.SimpleClass"

    HprofWriter.open(hprofFile, hprofVersion = ANDROID)
        .use { writer ->
          val classNameRecord = StringRecord(1L, className)
          writer.write(classNameRecord)
          writer.writeClass(42, classNameRecord, rootClass = true)
        }

    Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          assertThat(graph.findClassByName(className)).isNotNull
        }
  }

  @Test
  fun duplicatedClassesRootClassKeptInAndroid() {
    val className = "com.example.SimpleClass"

    HprofWriter.open(hprofFile, hprofVersion = ANDROID)
        .use { writer ->
          val classNameRecord = StringRecord(1L, className)
          writer.write(classNameRecord)
          writer.writeClass(42, classNameRecord, rootClass = true)
          writer.writeClass(24, classNameRecord, rootClass = false)
        }

    Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
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