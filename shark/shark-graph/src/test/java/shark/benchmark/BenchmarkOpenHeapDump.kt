package shark.benchmark

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.internal.SortedBytesMaps

/**
 * Opens the hprof at [args]\[0] and reports index build time, traversal time, random-access lookup
 * throughput and peak heap usage. [args]\[1] selects the index mode:
 *  - "default": pick single vs paged from the real 2 GB threshold.
 *  - "forcedPaged:N": force the paged index with N entries per page (to exercise paging on dumps
 *    smaller than 2 GB).
 *
 * Run via the :benchmarkOpenHeapDump Gradle task.
 */
fun main(args: Array<String>) {
  val path = args[0]
  val mode = args.getOrElse(1) { "default" }
  if (mode.startsWith("forcedPaged:")) {
    SortedBytesMaps.forcedEntriesPerPageForTesting = mode.substringAfter(":").toInt()
    println("Index mode: forced paged, entriesPerPage=${SortedBytesMaps.forcedEntriesPerPageForTesting}")
  } else if (mode == "forceSingle") {
    SortedBytesMaps.forceSingleArrayForTesting = true
    println("Index mode: forced single array (pre-paging behaviour)")
  } else {
    println("Index mode: default (paged only above ${SortedBytesMaps.MAX_SINGLE_ARRAY_BYTES} bytes)")
  }

  val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
  heapPools.forEach { it.resetPeakUsage() }
  System.gc()
  Thread.sleep(200)

  val tOpen = System.nanoTime()
  File(path).openHeapGraph().use { graph ->
    val openMs = (System.nanoTime() - tOpen) / 1_000_000
    println("openHeapGraph (parse + index build) = $openMs ms")

    val tTraverse = System.nanoTime()
    var objectCount = 0L
    val sampleIds = LongArray(1_000_000)
    var sampled = 0
    var seen = 0L
    var stride = 1L
    for (obj in graph.objects) {
      if (objectCount == 0L) {
        // Can't know total up front; sample opportunistically, trim later.
      }
      if (seen % stride == 0L && sampled < sampleIds.size) {
        sampleIds[sampled++] = obj.objectId
      }
      seen++
      objectCount++
    }
    val traverseMs = (System.nanoTime() - tTraverse) / 1_000_000
    println("enumerated $objectCount objects in $traverseMs ms (${rate(objectCount, traverseMs)})")

    val tLookup = System.nanoTime()
    var found = 0L
    for (k in 0 until sampled) {
      if (graph.findObjectByIdOrNull(sampleIds[k]) != null) found++
    }
    val lookupMs = (System.nanoTime() - tLookup) / 1_000_000
    println("looked up $sampled ids ($found found) in $lookupMs ms (${rate(sampled.toLong(), lookupMs)})")
  }

  System.gc()
  val peakMb = heapPools.sumOf { it.peakUsage.used } / 1024 / 1024
  println("peak heap used during run = $peakMb MB across pools: " +
    heapPools.joinToString { "${it.name}=${it.peakUsage.used / 1024 / 1024}MB" })
}

private fun rate(count: Long, ms: Long): String =
  if (ms <= 0) "n/a" else "${count * 1000 / ms}/s"
