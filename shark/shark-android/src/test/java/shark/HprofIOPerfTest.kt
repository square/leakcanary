package shark

import java.io.File
import kotlin.math.floor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.nield.kotlinstatistics.median
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * IO reads is the largest factor on Shark's performance so this helps prevents
 * regressions.
 */
class HprofIOPerfTest {

  @Test fun `HeapObjectArray#byteSize does not read`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val arrayId = hprofFile.openHeapGraph().use { graph ->
      graph.objectArrays.maxBy { it.readRecord().elementIds.size * graph.identifierByteSize }!!.objectId
    }

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      val readMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.findObjectById(arrayId).asObjectArray!!.byteSize
      readMetrics.byteCounts.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapObjectArray#byteSize correctly reads size of array`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    hprofFile.openHeapGraph().use { graph ->
      graph.objectArrays.forEach { array ->
        assertThat(array.byteSize).isEqualTo(
          array.readRecord().elementIds.size.toLong() * graph.identifierByteSize
        )
      }
    }
  }

  @Test fun `HeapPrimitiveArray#byteSize does not read`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val arrayId = hprofFile.openHeapGraph().use { graph ->
      graph.primitiveArrays.maxBy { it.readRecord().size * it.primitiveType.byteSize }!!.objectId
    }

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      val readMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.findObjectById(arrayId).asPrimitiveArray!!.byteSize
      readMetrics.byteCounts.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapPrimitiveArray#byteSize correctly reads size of array`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    hprofFile.openHeapGraph().use { graph ->
      graph.primitiveArrays.forEach { array ->
        assertThat(array.byteSize).isEqualTo(
          array.readRecord().size.toLong() * array.primitiveType.byteSize
        )
      }
    }
  }

  @Test fun `HeapInstance#byteSize reads 0 bytes`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      val readMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.instances.first().byteSize
      readMetrics.byteCounts.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `consecutive call to HeapObject#readRecord() reads 0 bytes`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      graph.objects.first().readRecord()
      val readMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.objects.first().readRecord()
      readMetrics.byteCounts.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapObject#readRecord() reads 0 bytes when reading from LRU`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      graph.objects.take(HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE).forEach { it.readRecord() }
      val readMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.objects.take(HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE).forEach { it.readRecord() }
      readMetrics.byteCounts.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapObject#readRecord() reads bytes when reading evicted object`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      graph.objects.take(HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE + 1).forEach { it.readRecord() }
      val readMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.objects.first().readRecord()
      readMetrics.byteCounts.sum()
    }

    assertThat(bytesRead).isGreaterThan(0)
  }

  @Test fun `analyze() creates 4 separate sources`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    // 4 phases: Read headers, fast scan, indexing, then random access for analysis.
    assertThat(metrics).hasSize(4)
  }

  @Test fun `header parsing requires only one segment`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val headerParsingReads = metrics[0].byteCounts
    assertThat(headerParsingReads).isEqualTo(listOf(OKIO_SEGMENT_SIZE))
  }

  @Test fun `fast scan pre indexing is a full file scan`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val fastScanReads = metrics[1].byteCounts
    val expectedReads = fullScanExpectedReads(hprofFile.length())
    assertThat(fastScanReads).hasSameSizeAs(expectedReads).isEqualTo(expectedReads)
  }

  @Test fun `indexing is a full file scan`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeIoReadMetrics(hprofFile)

    val indexingReads = metrics[2].byteCounts
    val expectedReads = fullScanExpectedReads(hprofFile.length())
    assertThat(indexingReads).hasSameSizeAs(expectedReads).isEqualTo(expectedReads)
  }

  @Test fun `freeze leak_asynctask_o hprof random access metrics`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val metrics = trackAnalyzeRandomAccessMetrics(hprofFile)

    assertThat(metrics.withoutRetainedSize.toString()).isEqualTo(
      "reads=19711 medianBytes=40.0 totalBytes=1021265 distinctPages=447 pageReads=19947"
    )
    assertThat(metrics.withRetainedSize.toString()).isEqualTo(
      "reads=20979 medianBytes=40.0 totalBytes=1078529 distinctPages=455 pageReads=21229"
    )
  }

  @Test fun `freeze leak_asynctask_m hprof random access metrics`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val metrics = trackAnalyzeRandomAccessMetrics(hprofFile)

    assertThat(metrics.withoutRetainedSize.toString()).isEqualTo(
      "reads=17407 medianBytes=40.0 totalBytes=1953885 distinctPages=696 pageReads=17885"
    )
    assertThat(metrics.withRetainedSize.toString()).isEqualTo(
      "reads=17412 medianBytes=40.0 totalBytes=1954065 distinctPages=696 pageReads=17890"
    )
  }

  @Test fun `freeze leak_asynctask_pre_m hprof random access metrics`() {
    val hprofFile = "leak_asynctask_pre_m.hprof".classpathFile()

    val metrics = trackAnalyzeRandomAccessMetrics(hprofFile)

    assertThat(metrics.withoutRetainedSize.toString()).isEqualTo(
      "reads=11787 medianBytes=32.0 totalBytes=554390 distinctPages=511 pageReads=11923"
    )
    assertThat(metrics.withRetainedSize.toString()).isEqualTo(
      "reads=11789 medianBytes=32.0 totalBytes=554454 distinctPages=511 pageReads=11925"
    )
  }

  class Reads(reads: List<Read>) {
    val readsCount = reads.size
    val medianBytesRead = reads.byteCounts.median()
    val totalBytesRead = reads.byteCounts.sum()
    val distinctPagesRead = reads.distinctPagesRead
    val pageReadCount = reads.pageReadCount

    /**
     * Frozen by the tests above, so that a change to how the analysis reads shows up as a failure
     * that says which of these numbers moved.
     */
    override fun toString() = "reads=$readsCount medianBytes=$medianBytesRead " +
      "totalBytes=$totalBytesRead distinctPages=$distinctPagesRead pageReads=$pageReadCount"
  }

  class AnalyzeReads(
    val withoutRetainedSize: Reads,
    val withRetainedSize: Reads
  )

  private fun trackAnalyzeRandomAccessMetrics(hprofFile: File) = AnalyzeReads(
    withoutRetainedSize = Reads(trackAnalyzeIoReadMetrics(hprofFile)[3]),
    withRetainedSize = Reads(
      trackAnalyzeIoReadMetrics(
        hprofFile,
        computeRetainedHeapSize = true,
        printResult = true
      )[3]
    )
  )

  private fun trackAnalyzeIoReadMetrics(
    hprofFile: File,
    computeRetainedHeapSize: Boolean = false,
    printResult: Boolean = false
  ): List<List<Read>> {
    val source = MetricsDualSourceProvider(hprofFile)
    val analysis = source.openHeapGraph().use { graph ->

      val leakingObjectFinder = FilteringLeakingObjectFinder(
        AndroidObjectInspectors.appLeakingObjectFilters
      )

      val objectIds = leakingObjectFinder.findLeakingObjectIds(graph)

      val referenceMatchers = AndroidReferenceMatchers.appDefaults

      val tracer = RealLeakTracerFactory(
        shortestPathFinderFactory = PrioritizingShortestPathFinder.Factory(
          listener = {},
          referenceReaderFactory = AndroidReferenceReaderFactory(referenceMatchers),
          gcRootProvider = MatchingGcRootProvider(referenceMatchers),
          objectSizeCalculatorFactory = if (computeRetainedHeapSize) {
            ObjectSizeCalculator.Factory { heapGraph -> AndroidObjectSizeCalculator(heapGraph) }
          } else {
            null
          },
        ),
        objectInspectors = AndroidObjectInspectors.appDefaults,
        listener = {}
      ).createFor(graph)

      tracer.traceObjects(objectIds)
    }
    if (printResult) {
      println(analysis)
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
    private const val HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE = 3000
  }
}
