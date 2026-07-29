package shark

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

class MetricsDualSourceProviderTest {

  /**
   * [ConstantMemoryMetricsDualSourceProvider] counts reads with atomics, so it's the reference for
   * how many reads actually happened.
   */
  @Test fun `records every read when several threads read at the same time`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val readCounter = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(hprofFile))
    val source = MetricsDualSourceProvider(readCounter)

    source.openHeapGraph().use { graph ->
      val objectIds = graph.instances.take(INSTANCE_COUNT).map { it.objectId }.toList()
      val executor = Executors.newFixedThreadPool(THREAD_COUNT)
      try {
        val startLine = CyclicBarrier(THREAD_COUNT)
        (0 until THREAD_COUNT).map { threadIndex ->
          executor.submit {
            startLine.await(TIMEOUT_SECONDS, SECONDS)
            // Each thread starts at a different object, so that they keep evicting each other's
            // records from the object cache and have to read again.
            val readOrder = objectIds.let { ids ->
              val offset = threadIndex * ids.size / THREAD_COUNT
              ids.drop(offset) + ids.take(offset)
            }
            readOrder.forEach { objectId ->
              graph.findObjectById(objectId).asInstance!!.readFields().forEach { it.value }
            }
          }
        }.forEach { it.get(TIMEOUT_SECONDS, SECONDS) }
      } finally {
        executor.shutdownNow()
      }
    }

    val randomAccessReads = source.sourcesMetrics.last()
    assertThat(randomAccessReads.size.toLong()).isEqualTo(readCounter.randomAccessReadCount)
    assertThat(randomAccessReads.byteCounts.sum().toLong())
      .isEqualTo(readCounter.randomAccessByteReads)
  }

  companion object {
    private const val THREAD_COUNT = 4
    private const val INSTANCE_COUNT = 5000
    private const val TIMEOUT_SECONDS = 60L
  }
}
