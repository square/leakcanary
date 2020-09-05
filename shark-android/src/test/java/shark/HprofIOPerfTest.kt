package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.nield.kotlinstatistics.median
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import kotlin.math.floor

/**
 * IO reads is the largest factor on Shark's performance so this helps prevents
 * regressions.
 */
class HprofIOPerfTest {

  @Test fun `analyze() creates 4 separate sources`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    // 4 phases: Read headers, fast scan, indexing, then random access for analysis.
    assertThat(metrics).hasSize(4)
  }

  @Test fun `header parsing requires only one segment`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val headerParsingReads = metrics[0]
    assertThat(headerParsingReads).isEqualTo(listOf(OKIO_SEGMENT_SIZE))
  }

  @Test fun `fast scan pre indexing is a full file scan`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val fastScanReads = metrics[1]
    val expectedReads = fullScanExpectedReads(hprofFile.length())
    assertThat(fastScanReads).hasSameSizeAs(expectedReads).isEqualTo(expectedReads)
  }

  @Test fun `indexing is a full file scan`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val indexingReads = metrics[2]
    val expectedReads = fullScanExpectedReads(hprofFile.length())
    assertThat(indexingReads).hasSameSizeAs(expectedReads).isEqualTo(expectedReads)
  }

  @Test fun `freeze leak_asynctask_o hprof random access metrics`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val randomAccessReads = metrics[3]
    val readsCount = randomAccessReads.size
    val medianBytesRead = randomAccessReads.median()
    val totalBytesRead = randomAccessReads.sum()
    assertThat(listOf(readsCount, medianBytesRead, totalBytesRead))
        .isEqualTo(listOf(28589, 40.0, 1849534))
  }

  @Test fun `freeze leak_asynctask_m hprof random access metrics`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val randomAccessReads = metrics[3]
    val readsCount = randomAccessReads.size
    val medianBytesRead = randomAccessReads.median()
    val totalBytesRead = randomAccessReads.sum()
    assertThat(listOf(readsCount, medianBytesRead, totalBytesRead))
        .isEqualTo(listOf(27198, 40.0, 2705246))
  }

  @Test fun `freeze leak_asynctask_pre_m hprof random access metrics`() {
    val hprofFile = "leak_asynctask_pre_m.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val randomAccessReads = metrics[3]
    val size = randomAccessReads.size
    val median = randomAccessReads.median()
    val sum = randomAccessReads.sum()
    assertThat(listOf(size, median, sum)).isEqualTo(listOf(20063, 32.0, 1105886))
  }

  private fun trackAnalyzeIoReadMetrics(hprofFile: File): List<List<Int>> {
    val source = MetricsDualSourceProvider(hprofFile)
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = source.openHeapGraph().use { graph ->
      heapAnalyzer.analyze(
          heapDumpFile = hprofFile,
          graph = graph,
          leakingObjectFinder = FilteringLeakingObjectFinder(
              AndroidObjectInspectors.appLeakingObjectFilters
          ),
          referenceMatchers = AndroidReferenceMatchers.appDefaults,
          computeRetainedHeapSize = false,
          objectInspectors = AndroidObjectInspectors.appDefaults,
          metadataExtractor = AndroidMetadataExtractor
      )
    }
    check(analysis is HeapAnalysisSuccess) {
      "Expected success not $analysis"
    }
    return source.sourcesMetrics
  }

  private fun fullScanExpectedReads(fileLength: Long): List<Int> {
    val fullReadsCount = floor(fileLength / OKIO_SEGMENT_SIZE.toDouble()).toInt()
    val remainderBytes = (fileLength - (OKIO_SEGMENT_SIZE * fullReadsCount)).toInt()

    val finalReads = if (remainderBytes > 0) listOf(remainderBytes, 0) else listOf(0)

    return List(fullReadsCount) {
      OKIO_SEGMENT_SIZE
    } + finalReads
  }

  companion object {
    private const val OKIO_SEGMENT_SIZE = 8192
  }
}