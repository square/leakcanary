package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

class CancelHeapGraphReadTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private var cancelReason: String? = null

  private val cancelSignal = CancelSignal { cancelReason }

  @Test fun `indexing a heap dump is canceled`() {
    val hprofFile = dumpHeap()
    cancelReason = CANCEL_REASON

    assertThatThrownBy { hprofFile.openHeapGraph(cancelSignal = cancelSignal) }
      .isInstanceOf(CanceledException::class.java)
      .hasMessage(CANCEL_REASON)
  }

  @Test fun `reading an object is canceled`() {
    val hprofFile = dumpHeap()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      val objectIds = graph.instances.map { it.objectId }.toList()

      cancelReason = CANCEL_REASON

      assertThatThrownBy { objectIds.forEach { graph.readFieldsOf(it) } }
        .isInstanceOf(CanceledException::class.java)
        .hasMessage(CANCEL_REASON)
    }
  }

  /**
   * Around a third of record reads are served from the object record cache without touching the
   * heap dump, so a cancel that's only noticed by a read would go unnoticed for as long as a
   * traversal keeps hitting that cache.
   */
  @Test fun `reading an object served from the record cache is canceled`() {
    val hprofFile = dumpHeap()
    val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(hprofFile))

    sourceProvider.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      val objectId = graph.instances.first().objectId

      // Fills the object record cache with everything reading this object needs.
      graph.readFieldsOf(objectId)
      val readCount = sourceProvider.randomAccessReadCount
      // Which the second read is then served from, so there's no read here to notice a cancel.
      graph.readFieldsOf(objectId)
      assertThat(sourceProvider.randomAccessReadCount).isEqualTo(readCount)

      cancelReason = CANCEL_REASON

      assertThatThrownBy { graph.readFieldsOf(objectId) }
        .isInstanceOf(CanceledException::class.java)
        .hasMessage(CANCEL_REASON)
      assertThat(sourceProvider.randomAccessReadCount).isEqualTo(readCount)
    }
  }

  @Test fun `a graph opened without a signal is never canceled`() {
    val hprofFile = dumpHeap()
    cancelReason = CANCEL_REASON

    hprofFile.openHeapGraph().use { graph ->
      assertThat(graph.cancelSignal).isSameAs(CancelSignal.NEVER)
      graph.instances.forEach { graph.readFieldsOf(it.objectId) }
    }
  }

  @Test fun `a signal that never cancels lets the whole heap dump be read`() {
    val hprofFile = dumpHeap()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      assertThat(graph.instances.count()).isEqualTo(INSTANCE_COUNT * 2)
    }
  }

  private fun HeapGraph.readFieldsOf(objectId: Long) =
    findObjectById(objectId).asInstance!!.readFields().toList()

  /**
   * More instances than [HprofHeapGraph.INTERNAL_LRU_CACHE_SIZE] would fit, so that indexing has
   * enough to do for a cancel to land in the middle of it.
   */
  private fun dumpHeap(): File {
    val hprofFile = testFolder.newFile("cancel-reads.hprof")
    hprofFile.dump {
      val instanceClassId = clazz(
        className = "com.example.SomeClass",
        fields = listOf("name" to ReferenceHolder::class, "index" to IntHolder::class)
      )
      // Each instance also creates a String instance and the char array backing it.
      for (index in 0 until INSTANCE_COUNT) {
        instance(
          classId = instanceClassId,
          fields = listOf(string("Instance number $index"), IntHolder(index))
        )
      }
    }
    return hprofFile
  }

  companion object {
    private const val INSTANCE_COUNT = 4000
    private const val CANCEL_REASON = "canceled by a test"
  }
}
