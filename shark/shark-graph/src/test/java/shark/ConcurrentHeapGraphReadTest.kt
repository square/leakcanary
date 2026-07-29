package shark

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder

class ConcurrentHeapGraphReadTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  @Test fun `concurrent reads read the same values as a single threaded read`() {
    val hprofFile = dumpHeapWithManyInstances()

    hprofFile.openHeapGraph().use { graph ->
      val expectedDescriptions = graph.instances.map { it.describe() }.toList()

      val concurrentDescriptions = runConcurrently {
        graph.instances.map { it.describe() }.toList()
      }

      concurrentDescriptions.forEach { descriptions ->
        assertThat(descriptions).isEqualTo(expectedDescriptions)
      }
    }
  }

  @Test fun `concurrent reads of the same objects in a different order read the same values`() {
    val hprofFile = dumpHeapWithManyInstances()

    hprofFile.openHeapGraph().use { graph ->
      val expectedDescriptionByObjectId = graph.instances.associate { it.objectId to it.describe() }
      val objectIds = expectedDescriptionByObjectId.keys.toList()

      // Every thread reads all the objects, starting at a different one, so that the reads keep
      // hitting and evicting each other's entries in the object record cache.
      val concurrentDescriptionsByObjectId = runConcurrently { threadIndex ->
        val readOrder = objectIds.shifted(by = threadIndex * objectIds.size / THREAD_COUNT)
        readOrder.associate { objectId ->
          objectId to graph.findObjectById(objectId).asInstance!!.describe()
        }
      }

      concurrentDescriptionsByObjectId.forEach { descriptionByObjectId ->
        assertThat(descriptionByObjectId).isEqualTo(expectedDescriptionByObjectId)
      }
    }
  }

  @Test fun `concurrent getOrPut on the graph context all read the same value`() {
    val hprofFile = dumpHeapWithManyInstances()

    hprofFile.openHeapGraph().use { graph ->
      val contextValues = runConcurrently {
        graph.context.getOrPut("key") { Any() }
      }

      assertThat(contextValues.distinct()).hasSize(1)
      assertThat(graph.context.get<Any>("key")).isSameAs(contextValues.first())
    }
  }

  /**
   * More instances than [HprofHeapGraph.INTERNAL_LRU_CACHE_SIZE], so that reads keep evicting each
   * other's records from the object record cache.
   */
  private fun dumpHeapWithManyInstances(): File {
    val hprofFile = testFolder.newFile("concurrent-reads.hprof")
    hprofFile.dump {
      val instanceClassId = clazz(
        className = "com.example.SomeClass",
        fields = listOf(
          "name" to ReferenceHolder::class,
          "index" to IntHolder::class,
          "createdAtUptimeMillis" to LongHolder::class
        )
      )
      // Each instance also creates a String instance and the char array backing it.
      for (index in 0 until INSTANCE_COUNT) {
        instance(
          classId = instanceClassId,
          fields = listOf(
            string("Instance number $index"),
            IntHolder(index),
            LongHolder(index * 1000L)
          )
        )
      }
    }
    return hprofFile
  }

  /**
   * Reads every field of this instance, following reference fields, so that a read that returns the
   * wrong bytes shows up as a different description.
   */
  private fun HeapInstance.describe(): String {
    val fields = readFields().joinToString(prefix = "{", postfix = "}") { field ->
      val value = field.value
      val describedValue = if (value.isNonNullReference) {
        value.readAsJavaString() ?: "object ${value.asObject!!.objectId}"
      } else {
        value.holder.toString()
      }
      "${field.declaringClass.name}.${field.name}=$describedValue"
    }
    return "$instanceClassName$fields"
  }

  private fun <T> List<T>.shifted(by: Int): List<T> = drop(by) + take(by)

  /**
   * Runs [readBlock] on [THREAD_COUNT] threads at once, passing each of them its own thread index,
   * and returns what each of them read.
   */
  private fun <T> runConcurrently(readBlock: (Int) -> T): List<T> {
    val executor = Executors.newFixedThreadPool(THREAD_COUNT)
    try {
      // All threads wait for each other before reading, so that the reads do overlap.
      val startLine = CyclicBarrier(THREAD_COUNT)
      val readings = (0 until THREAD_COUNT).map { threadIndex ->
        executor.submit(Callable {
          startLine.await(TIMEOUT_SECONDS, SECONDS)
          readBlock(threadIndex)
        })
      }
      return readings.map { it.get(TIMEOUT_SECONDS, SECONDS) }
    } finally {
      executor.shutdownNow()
    }
  }

  companion object {
    private const val THREAD_COUNT = 4
    private const val INSTANCE_COUNT = 1500
    private const val TIMEOUT_SECONDS = 60L
  }
}
