package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Test
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.internal.SortedBytesMaps

/**
 * Exercises the paged index code path ([shark.internal.PagedSortedBytesMap] etc.) end to end through
 * a real [HeapGraph], by forcing a tiny page size so a small heap dump spreads each per-record-type
 * index across many pages. The resulting graph must be identical to the one produced by the single
 * array index path.
 */
class PagedHprofIndexIntegrationTest {

  @After fun tearDown() {
    SortedBytesMaps.forcedEntriesPerPageForTesting = null
  }

  @Test fun `paged index yields the same graph as the single array index`() {
    val provider = dump {
      val nodeClass = clazz(
        className = "com.example.Node",
        fields = listOf("next" to ReferenceHolder::class, "value" to IntHolder::class)
      )
      // A few extra classes so the class index itself spans several pages.
      repeat(6) { clazz(className = "com.example.Extra$it") }

      val instances = mutableListOf<ReferenceHolder>()
      var previous = ReferenceHolder(ValueHolder.NULL_REFERENCE)
      repeat(20) { i ->
        previous = instance(nodeClass, fields = listOf(previous, IntHolder(i)))
        instances += previous
      }
      // Object arrays referencing instances, and primitive arrays, so all four indexes are paged.
      repeat(6) { i ->
        objectArray(instances[i], instances[i + 1], instances[i + 2])
      }
      repeat(6) { i ->
        primitiveLongArray(longArrayOf(i.toLong(), (i * 2).toLong(), 42L))
      }
    }

    val singleArraySummary = summarize(provider)
    SortedBytesMaps.forcedEntriesPerPageForTesting = 4
    val pagedSummary = summarize(provider)

    assertThat(pagedSummary).isEqualTo(singleArraySummary)
    // Sanity check that the dump is non-trivial (classes + 20 instances + arrays + helper objects).
    assertThat(singleArraySummary.size).isGreaterThan(40)
  }

  /**
   * Walks every object in the graph, resolving each object and every outgoing reference through the
   * index, and returns a stable, comparable description of the whole graph.
   */
  private fun summarize(provider: DualSourceProvider): List<String> {
    return provider.openHeapGraph().use { graph ->
      graph.objects.map { obj ->
        val objectId = obj.objectId
        // Resolve by id (indexOf path) and confirm the round trip.
        check(graph.findObjectById(objectId).objectId == objectId)
        val references = when (obj) {
          is HeapInstance -> obj.readFields().mapNotNull { it.value.asObjectId }
          is HeapObjectArray -> obj.readElements().mapNotNull { it.asObjectId }
          else -> emptySequence()
        }.filter { it != ValueHolder.NULL_REFERENCE }.toList()
        // Every referenced id must resolve through the index too.
        references.forEach { check(graph.findObjectByIdOrNull(it) != null) { "missing $it" } }
        val type = obj::class.java.simpleName
        "${graph.findHeapDumpIndex(objectId)}|$objectId|$type|refs=${references.sorted()}"
      }.toList().sorted()
    }
  }
}
