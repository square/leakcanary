package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapAnalyzer
import leakcanary.LeakingInstance
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HeapAnalyzerTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test
  fun findMultipleLeaks() {
    val hprofFile = testFolder.newFile("temp.hprof")

    hprofFile.dumpMultipleActivityLeaks(5)

    val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)

    val leaks =
      heapAnalyzer.checkForLeaks(
          hprofFile, defaultExclusionFactory, false, emptyList(), emptyList()
      )
    assertThat(leaks).isInstanceOf(HeapAnalysisSuccess::class.java)
    leaks as HeapAnalysisSuccess

    assertThat(leaks.retainedInstances).hasSize(5)
        .hasOnlyElementsOfType(LeakingInstance::class.java)
  }
}