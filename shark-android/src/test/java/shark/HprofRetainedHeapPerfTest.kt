package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

class HprofRetainedHeapPerfTest {

  @get:Rule
  var tmpFolder = TemporaryFolder()

  @Test fun `freeze retained memory when indexing leak_asynctask_o`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val hprofIndex = indexRecordsOf(hprofFile)

    val testHprofFile = dumpHeapRetaining(hprofIndex)

    val retained = testHprofFile.findRetainedInstanceOf(HprofIndex::class)
    assertThat(retained.totalRetainedHeapByteSize).isEqualTo(4_696_968)
  }

  @Test fun `freeze retained memory when indexing leak_asynctask_m`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val hprofIndex = indexRecordsOf(hprofFile)

    val testHprofFile = dumpHeapRetaining(hprofIndex)

    val retained = testHprofFile.findRetainedInstanceOf(HprofIndex::class)
    assertThat(retained.totalRetainedHeapByteSize).isEqualTo(8_059_348)
  }

  private fun indexRecordsOf(hprofFile: File): HprofIndex {
    return HprofIndex.indexRecordsOf(
        hprofSourceProvider = FileSourceProvider(hprofFile),
        hprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    )
  }

  private fun dumpHeap(): File {
    val testHprofFile = File(tmpFolder.newFolder(), "test_heapdump.hprof")
    JvmTestHeapDumper.dumpHeap(testHprofFile.absolutePath)
    return testHprofFile
  }

  private fun dumpHeapRetaining(instance: Any): File {
    val heapDumpFile = dumpHeap()
    // Dumb check to prevent instance from being garbage collected.
    check(instance::class::class.isInstance(KClass::class))
    return heapDumpFile
  }

  private fun File.findRetainedInstanceOf(
    indexClass: KClass<HprofIndex>
  ): ApplicationLeak {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
        heapDumpFile = this,
        leakingObjectFinder = LeakingObjectFinder { graph ->
          setOf(graph.findClassByName(indexClass.jvmName)!!.instances.first().objectId)
        },
        computeRetainedHeapSize = true
    )
    check(analysis is HeapAnalysisSuccess) {
      "Expected success not $analysis"
    }

    val leak = analysis.applicationLeaks[0]
    return leak
  }

}