package shark.internal

import shark.HeapGraph
import shark.HprofHeapGraph
import shark.internal.hppc.LongScatterSet

/**
 * The object index of [objectId], or -1 if this heap graph holds no object with that id.
 *
 * [HprofHeapGraph] resolves this without reading the object it points to, which is what makes
 * [ObjectIdSet] affordable. Any other implementation pays for reading it.
 */
private fun HeapGraph.objectIndexOrNull(objectId: Long): Int {
  return if (this is HprofHeapGraph) {
    objectIndexOrNull(objectId)
  } else {
    findObjectByIdOrNull(objectId)?.objectIndex ?: -1
  }
}

/**
 * A set of the object ids of a [HeapGraph], stored as one bit per object index (the index
 * [HeapGraph.findObjectByIndex] takes).
 *
 * Object indexes are dense in `[0, objectCount[` and [HeapGraph.objectCount] is an [Int], so the
 * whole set is a single [LongArray] of at most 268 MB whatever the heap dump: it's sized exactly up
 * front, never grows and never rehashes. Keying on object ids instead means hashing 8 byte
 * addresses, which costs `nextPowerOfTwo(ceil(count / 0.75)) * 8` bytes — 86 to 143 times more on
 * the heap dumps we measured — and caps out at 805306368 ids, [HPPC][shark.internal.hppc.HPPC]
 * refusing to grow its hash array past 2^30 slots.
 *
 * What that buys costs a lookup of the object index of every id added, which is a binary search
 * over the heap dump index rather than a hash of the id.
 */
internal class ObjectIdSet(private val graph: HeapGraph) {

  private val words = LongArray((graph.objectCount + WORD_MASK) ushr WORD_SHIFT)

  /**
   * Ids the heap dump holds no object for, and which therefore have no object index. GC roots can
   * point to objects that aren't in the heap dump, so a traversal does reach those.
   */
  private val unknownObjectIds = LongScatterSet()

  /** Returns true if [objectId] wasn't in the set yet. */
  fun add(objectId: Long): Boolean {
    val objectIndex = graph.objectIndexOrNull(objectId)
    if (objectIndex < 0) {
      return unknownObjectIds.add(objectId)
    }
    val wordIndex = objectIndex ushr WORD_SHIFT
    val bit = 1L shl (objectIndex and WORD_MASK)
    val word = words[wordIndex]
    if (word and bit != 0L) {
      return false
    }
    words[wordIndex] = word or bit
    return true
  }

  operator fun contains(objectId: Long): Boolean {
    val objectIndex = graph.objectIndexOrNull(objectId)
    if (objectIndex < 0) {
      return objectId in unknownObjectIds
    }
    return words[objectIndex ushr WORD_SHIFT] and (1L shl (objectIndex and WORD_MASK)) != 0L
  }

  companion object {
    private const val WORD_SHIFT = 6
    private const val WORD_MASK = (1 shl WORD_SHIFT) - 1
  }
}
