package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
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
    val objectArray = arrayOfNulls<JvmHprofParsingTest>(0)
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    // Dumb check to prevent instance from being garbage collected.
    check(objectArray::class::class.isInstance(KClass::class))

    val expectedArrayClassName = "${JvmHprofParsingTest::class.java.name}[]"

    hprofFile.openHeapGraph().use { graph ->
      val arrayClass = graph.findClassByName(expectedArrayClassName)
      assertThat(arrayClass).isNotNull
      assertThat(arrayClass!!.isObjectArrayClass).isTrue()
      assertThat(arrayClass!!.name).isEqualTo(expectedArrayClassName)

      val arrayInstances = arrayClass.objectArrayInstances.toList()
      assertThat(arrayInstances).hasSize(1)
      val array = arrayInstances.single()
      assertThat(array.readByteSize()).isEqualTo(0)
    }
  }
}

private val KClass<out Any>.name: String
  get() = this.java.name