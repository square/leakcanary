package shark.internal

import shark.HeapGraph
import shark.HprofHeapGraph

/**
 * A set of heap object ids, backed by one bit per object in the heap dump: an object id is stored
 * as its `objectIndex`, which is dense over `[0, HeapGraph.objectCount[`.
 *
 * That's 1 bit per object in the dump where a set of ids keyed by hash needs ~10.7 bytes per object
 * it holds, and the backing array is allocated once at a size known upfront, so it never grows and
 * never rehashes. That matters for a set that ends up holding every object reachable from the GC
 * roots: growing one is what ran the heap analysis out of memory on large Android heaps.
 *
 * In exchange, mapping an object id to its index is a binary search rather than a hash lookup.
 *
 * An object id that isn't in the heap dump has no index and therefore can't be stored: [add]
 * reports it as newly added every time. A heap dump that references an object it doesn't contain is
 * corrupt, and every caller either throws when it looks that id up or ignores it, so repeating
 * "newly added" changes nothing.
 */
internal class HeapObjectIdSet(private val graph: HeapGraph) {

  private val words = LongArray((graph.objectCount + BITS_PER_WORD - 1) / BITS_PER_WORD)

  /**
   * Adds [objectId] to this set, returning true if it wasn't in the set already.
   */
  fun add(objectId: Long): Boolean {
    val objectIndex = objectIndexOrMinusOne(objectId)
    if (objectIndex == -1) {
      return true
    }
    val wordIndex = objectIndex / BITS_PER_WORD
    val bit = 1L shl (objectIndex % BITS_PER_WORD)
    val word = words[wordIndex]
    if (word and bit != 0L) {
      return false
    }
    words[wordIndex] = word or bit
    return true
  }

  private fun objectIndexOrMinusOne(objectId: Long): Int {
    return if (graph is HprofHeapGraph) {
      graph.objectIndexOrMinusOne(objectId)
    } else {
      // Correct for any other HeapGraph implementation, at the cost of an allocation per lookup.
      graph.findObjectByIdOrNull(objectId)?.objectIndex ?: -1
    }
  }

  companion object {
    private const val BITS_PER_WORD = 64
  }
}
