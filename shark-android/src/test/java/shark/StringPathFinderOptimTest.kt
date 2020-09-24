package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * Our path finding algorithm skips going through the content of strings to avoid unnecessary reads.
 * We add back the corresponding size when computing the shallow size of retained strings,
 * however that only works if those byte arrays aren't reachable through other references.
 * If they were, this could either inflate the retained size number (byte array should not be
 * considered retained) or deflate it (byte array counted twice when reached from two retained
 * instances).
 */
class StringPathFinderOptimTest {

  @Test fun `String#value not reachable on Android O`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val analysis = findStringContent(hprofFile)
    assertThat(analysis.allLeaks.count()).isEqualTo(0)
  }

  @Test fun `String#value not reachable on Android M`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()
    val analysis = findStringContent(hprofFile)
    assertThat(analysis.allLeaks.count()).isEqualTo(0)
  }

  @Test fun `String#value only reachable for String#ASCII pre Android M`() {
    val hprofFile = "leak_asynctask_pre_m.hprof".classpathFile()
    val analysis = findStringContent(hprofFile)
    assertThat(analysis.allLeaks.count()).isEqualTo(1)
    val path = analysis.applicationLeaks.first().leakTraces.first()
    assertThat(path.referencePath.first().referenceName).isEqualTo("ASCII")
  }

  private fun findStringContent(hprofFile: File): HeapAnalysisSuccess {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
        heapDumpFile = hprofFile,
        leakingObjectFinder = LeakingObjectFinder { graph ->
          graph.findClassByName("java.lang.String")!!.instances.map { instance ->
            instance["java.lang.String", "value"]?.value?.asNonNullObjectId!!
          }.toSet()
        },
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        computeRetainedHeapSize = true,
        objectInspectors = AndroidObjectInspectors.appDefaults,
        metadataExtractor = AndroidMetadataExtractor
    )
    println(analysis)
    return analysis as HeapAnalysisSuccess
  }
}