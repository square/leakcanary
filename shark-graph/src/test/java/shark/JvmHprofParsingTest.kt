package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapObjectArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.PrimitiveType.INT
import java.io.File
import kotlin.reflect.KClass

class JvmHprofParsingTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun dumpHeapAndReadString() {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")

    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)

    hprofFile.openHeapGraph().use { graph ->
      val testInstances = graph.instances
          .filter { it.instanceClassName == JvmHprofParsingTest::class.name }
          .toList()

      assertThat(testInstances).hasSize(1)
      val test = testInstances[0]
      val folderPath = test[JvmHprofParsingTest::class.name, "testFolder"]!!
          .valueAsInstance!![TemporaryFolder::class.name, "folder"]!!
          .valueAsInstance!![File::class.name, "path"]!!
          .value.readAsJavaString()!!

      assertThat(folderPath).isEqualTo(testFolder.root.path)
    }
  }

  @Test fun `JVM object array class name is translated to brackets`() {
    val objectArray = arrayOf<JvmHprofParsingTest>()
    val hprofFile = dumpHeapRetaining(objectArray)

    val expectedArrayClassName = "${JvmHprofParsingTest::class.java.name}[]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isObjectArrayClass).isTrue()
      assertThat(arrayClass.name).isEqualTo(expectedArrayClassName)

      val array = arrayClass.objectArrayInstances.single()
      assertThat(array.arrayClassName).isEqualTo(expectedArrayClassName)
    }
  }

  @Test fun `JVM multi dimension object array class name is translated to brackets`() {
    val objectArray = arrayOf(arrayOf(), arrayOf<JvmHprofParsingTest>())
    val hprofFile = dumpHeapRetaining(objectArray)

    val expectedArrayClassName = "${JvmHprofParsingTest::class.java.name}[][]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isObjectArrayClass).isTrue()
      assertThat(arrayClass.name).isEqualTo(expectedArrayClassName)

      val array = arrayClass.objectArrayInstances.single()
      assertThat(array.arrayClassName).isEqualTo(expectedArrayClassName)
    }
  }

  @Test fun `JVM primitive wrapper array class name is translated to brackets`() {
    val hprofFile = dumpHeapRetaining(arrayOfNulls<Int?>(42))

    val expectedArrayClassName = "java.lang.Integer[]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isObjectArrayClass).isTrue()
      assertThat(arrayClass.name).isEqualTo(expectedArrayClassName)

      val array = arrayClass.objectArrayInstances.single { it.readElements().count() == 42 }
      assertThat(array.arrayClassName).isEqualTo(expectedArrayClassName)
    }
  }

  @Test fun `JVM multi dimension wrapper primitive array class name is translated to brackets`() {
    val hprofFile = dumpHeapRetaining(arrayOf(arrayOf(), arrayOf<Int?>()))

    val expectedArrayClassName = "java.lang.Integer[][]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isObjectArrayClass).isTrue()
      assertThat(arrayClass.name).isEqualTo(expectedArrayClassName)

      val array = arrayClass.objectArrayInstances.single { it.readElements().count() == 2 }
      assertThat(array.arrayClassName).isEqualTo(expectedArrayClassName)
    }
  }

  @Test fun `JVM primitive array class name is translated to brackets`() {
    val hprofFile = dumpHeapRetaining(IntArray(42).apply { this[0] = 0xDad })

    val expectedArrayClassName = "int[]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isPrimitiveArrayClass).isTrue()
      assertThat(arrayClass.name).isEqualTo(expectedArrayClassName)

      val array = arrayClass.primitiveArrayInstances.single {
        it.primitiveType == INT && it.readRecord()
            .run { size == 42 && (this as IntArrayDump).array[0] == 0xDad }
      }
      assertThat(array.arrayClassName).isEqualTo(expectedArrayClassName)
    }
  }

  @Test fun `JVM multi dimension primitive array class name is translated to brackets`() {
    val hprofFile = dumpHeapRetaining(arrayOf(IntArray(42), IntArray(42)))

    val expectedArrayClassName = "int[][]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isPrimitiveArrayClass).isFalse()
      assertThat(arrayClass.name).isEqualTo(expectedArrayClassName)

      val array = arrayClass.objectArrayInstances.single {
        it.readRecord().elementIds.size == 2
      }
      assertThat(array.arrayClassName).isEqualTo(expectedArrayClassName)
    }
  }

  private fun dumpHeapRetaining(retained: Any): File {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    // Dumb check to prevent instance from being garbage collected.
    check(retained::class::class.isInstance(KClass::class))
    return hprofFile
  }

}

private val KClass<out Any>.name: String
  get() = this.java.name