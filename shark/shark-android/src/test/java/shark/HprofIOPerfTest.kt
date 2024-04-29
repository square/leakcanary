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

  @Test fun `HeapObjectArray#readByteSize() does not read`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val arrayId = hprofFile.openHeapGraph().use { graph ->
      graph.objectArrays.maxBy { it.readRecord().elementIds.size * graph.identifierByteSize }!!.objectId
    }

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      val bytesReadMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.findObjectById(arrayId).asObjectArray!!.byteSize
      bytesReadMetrics.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapObjectArray#byteSize correctly reads size of array`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    hprofFile.openHeapGraph().use { graph ->
      graph.objectArrays.forEach { array ->
        assertThat(array.byteSize).isEqualTo(
          array.readRecord().elementIds.size * graph.identifierByteSize
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
      val bytesReadMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.findObjectById(arrayId).asPrimitiveArray!!.byteSize
      bytesReadMetrics.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapPrimitiveArray#readByteSize() correctly reads size of array`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    hprofFile.openHeapGraph().use { graph ->
      graph.primitiveArrays.forEach { array ->
        assertThat(array.byteSize).isEqualTo(
          array.readRecord().size * array.primitiveType.byteSize
        )
      }
    }
  }

  @Test fun `HeapInstance#byteSize reads 0 bytes`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      val bytesReadMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.instances.first().byteSize
      bytesReadMetrics.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `consecutive call to HeapObject#readRecord() reads 0 bytes`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      graph.objects.first().readRecord()
      val bytesReadMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.objects.first().readRecord()
      bytesReadMetrics.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapObject#readRecord() reads 0 bytes when reading from LRU`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      graph.objects.take(HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE).forEach { it.readRecord() }
      val bytesReadMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.objects.take(HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE).forEach { it.readRecord() }
      bytesReadMetrics.sum()
    }

    assertThat(bytesRead).isEqualTo(0)
  }

  @Test fun `HeapObject#readRecord() reads bytes when reading evicted object`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val source = MetricsDualSourceProvider(hprofFile)

    val bytesRead = source.openHeapGraph().use { graph ->
      graph.objects.take(HPROF_HEAP_GRAPH_LRU_OBJECT_CACHE_SIZE + 1).forEach { it.readRecord() }
      val bytesReadMetrics = source.sourcesMetrics.last().apply { clear() }
      graph.objects.first().readRecord()
      bytesReadMetrics.sum()
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

    val metrics = trackAnalyzeRandomAccessMetrics(hprofFile)

    assertThat(
      listOf(
        metrics.first.readsCount, metrics.first.medianBytesRead, metrics.first.totalBytesRead,
        metrics.second.readsCount, metrics.second.medianBytesRead, metrics.second.totalBytesRead
      )
    )
      .isEqualTo(
        listOf(
          19713, 40.0, 1021868, 20981, 40.0, 1079140
        )
      )
  }

  @Test fun `freeze leak_asynctask_m hprof random access metrics`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val metrics = trackAnalyzeRandomAccessMetrics(hprofFile)

    assertThat(
      listOf(
        metrics.first.readsCount, metrics.first.medianBytesRead, metrics.first.totalBytesRead,
        metrics.second.readsCount, metrics.second.medianBytesRead, metrics.second.totalBytesRead
      )
    )
      .isEqualTo(
        listOf(
          17408, 40.0, 1954105, 17413, 40.0, 1954285
        )
      )
  }

  @Test fun `freeze leak_asynctask_pre_m hprof random access metrics`() {
    val hprofFile = "leak_asynctask_pre_m.hprof".classpathFile()

    val metrics = trackAnalyzeRandomAccessMetrics(hprofFile)

    assertThat(
      listOf(
        metrics.first.readsCount, metrics.first.medianBytesRead, metrics.first.totalBytesRead,
        metrics.second.readsCount, metrics.second.medianBytesRead, metrics.second.totalBytesRead
      )
    )
      .isEqualTo(
        listOf(
          11787, 32.0, 554413, 11789, 32.0, 554477
        )
      )
  }

  class Reads(reads: List<Int>) {
    val readsCount = reads.size
    val medianBytesRead = reads.median()
    val totalBytesRead = reads.sum()
  }

  private fun trackAnalyzeRandomAccessMetrics(hprofFile: File): Pair<Reads, Reads> {
    return trackAnalyzeIoReadMetrics(hprofFile).run {
      Reads(this[3])
    } to trackAnalyzeIoReadMetrics(
      hprofFile,
      computeRetainedHeapSize = true,
      printResult = true
    ).run {
      Reads(this[3])
    }
  }

  private fun trackAnalyzeIoReadMetrics(
    hprofFile: File,
    computeRetainedHeapSize: Boolean = false,
    printResult: Boolean = false
  ): List<List<Int>> {
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
          computeRetainedHeapSize = computeRetainedHeapSize,
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
