package leakcanary.internal

import leakcanary.HprofGraph
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.reflect.KClass

class JvmHprofParsingTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun dumpHeapAndReadString() {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")

    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)

    val (graph, closeable) = HprofGraph.readHprof(hprofFile)

    closeable.use {
      val testInstances = graph.instances
          .filter { it.className == JvmHprofParsingTest::class.name }
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
}

private val KClass<out Any>.name: String
  get() = this.java.name