package shark

import okio.buffer
import okio.source
import shark.AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
import shark.AndroidReferenceMatchers.REFERENCES
import shark.HprofHeapGraph.Companion.openHeapGraph
import java.io.File
import java.io.FileWriter
import java.util.EnumSet
import java.util.UUID

/**
 * Tests different values for the lru cache size when performing the leak analysss, measuring IO
 * reads and memory retained by the cache for each cache size, and outputting the result as
 * a CSV.
 *
 * Data saved at https://docs.google.com/spreadsheets/d/14BRd1CJO2_WRBqNQRdfDUDLhr3_2R5N461a74XA4pjE/edit?usp=sharing
 */
fun main() {
  val tmpHeapDumpFolder = createTemporaryFolder()

  val lruCacheSizes = 100..20000 step 500
  val files = listOf("leak_asynctask_o", "leak_asynctask_m")
  val computeRetainedHeapSizeList = listOf(false, true)

  val stats = mutableListOf<MutableList<Int>>()
  for (lruCacheSize in lruCacheSizes) {
    val row = mutableListOf<Int>()
    row.add(lruCacheSize)
    stats.add(row)
  }

  for (filename in files) {
    val hprofFile = "$filename.hprof".classpathFile()
    val bytes = hprofFile.inputStream().source().buffer().readByteArray()
    lruCacheSizes.forEachIndexed { index, lruCacheSize ->
      val row = stats[index]
      for (computeRetainedHeapSize in computeRetainedHeapSizeList) {
        val (randomAccessReads, lruRetainedSize) = trackAnalyzeMetrics(
            hprofFile, bytes, tmpHeapDumpFolder, computeRetainedHeapSize, lruCacheSize
        )
        val bytesRead = randomAccessReads.sum()
        val readCount = randomAccessReads.size
        row.add(bytesRead)
        row.add(readCount)
        row.add(lruRetainedSize)
      }
    }
  }

  tmpHeapDumpFolder.recursiveDelete()

  FileWriter("lru_cache_tuning.csv").use {
    with(it) {
      append("lru_size")
      for (filename in files) {
        for (computeRetainedHeapSize in computeRetainedHeapSizeList) {
          listOf("bytes_read", "read_count", "lru_retained").forEach { column ->
            append(",${filename}_size_${computeRetainedHeapSize}_$column")
          }
        }
      }
      append('\n')
      for (statRow in stats) {
        append(statRow.joinToString(",", postfix = "\n"))
      }
    }
  }
}

private fun trackAnalyzeMetrics(
  hprofFile: File,
  bytes: ByteArray,
  tmpHeapDumpFolder: File,
  computeRetainedHeapSize: Boolean,
  lruCacheSize: Int
): Pair<List<Int>, Int> {
  println(
      "Analysing ${hprofFile.name} computeRetainedHeapSize=$computeRetainedHeapSize lruCacheSize=$lruCacheSize"
  )

  val source = MetricsDualSourceProvider(ByteArraySourceProvider(bytes))
  val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
  val heapAfterAnalysis = withLruCacheSize(lruCacheSize) {
    source.openHeapGraph().use { graph ->
      val analysis = heapAnalyzer.analyze(
          heapDumpFile = hprofFile,
          graph = graph,
          leakingObjectFinder = FilteringLeakingObjectFinder(
              AndroidObjectInspectors.appLeakingObjectFilters
          ),
          referenceMatchers = AndroidReferenceMatchers.appDefaults,
          computeRetainedHeapSize = computeRetainedHeapSize,
          objectInspectors = AndroidObjectInspectors.appDefaults,
          metadataExtractor = AndroidMetadataExtractor
      )
      check(analysis is HeapAnalysisSuccess) {
        "Expected success not $analysis"
      }
      tmpHeapDumpFolder.dumpHeap()
    }
  }
  val randomAccessReads = source.sourcesMetrics[3]

  val lruCacheAnalysis = heapAnalyzer.analyze(
      heapDumpFile = heapAfterAnalysis,
      referenceMatchers = AndroidReferenceMatchers.buildKnownReferences(
          EnumSet.of(REFERENCES, FINALIZER_WATCHDOG_DAEMON)
      ),
      leakingObjectFinder = LeakingObjectFinder { graph ->
        setOf(graph.findClassByName("shark.internal.LruCache")!!.instances.single().objectId)
      },
      computeRetainedHeapSize = true
  )
  check(lruCacheAnalysis is HeapAnalysisSuccess) {
    "Expected success not $lruCacheAnalysis"
  }
  val lruRetainedSize =
    lruCacheAnalysis.allLeaks.single().leakTraces.single().retainedHeapByteSize!!

  println(
      "${randomAccessReads.sum()} bytes in ${randomAccessReads.size} reads, retaining $lruRetainedSize bytes in cache"
  )

  return randomAccessReads to lruRetainedSize
}

private fun <T> withLruCacheSize(
  lruCacheSize: Int,
  block: () -> T
): T {
  val sizeBefore = HprofHeapGraph.INTERNAL_LRU_CACHE_SIZE
  HprofHeapGraph.INTERNAL_LRU_CACHE_SIZE = lruCacheSize
  try {
    return block()
  } finally {
    HprofHeapGraph.INTERNAL_LRU_CACHE_SIZE = sizeBefore
  }
}

private fun createTemporaryFolder(): File {
  val createdFolder = File.createTempFile("shark", "", null)
  createdFolder.delete()
  createdFolder.mkdir()
  return createdFolder
}

private fun File.recursiveDelete() {
  val files = listFiles()
  if (files != null) {
    for (each in files) {
      each.recursiveDelete()
    }
  }
  delete()
}

private fun File.dumpHeap(): File {
  val testHprofFile = File(this, "${UUID.randomUUID()}.hprof")
  JvmTestHeapDumper.dumpHeap(testHprofFile.absolutePath)
  return testHprofFile
}