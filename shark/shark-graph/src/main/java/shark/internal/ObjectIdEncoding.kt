package shark.internal

import kotlin.math.max

/**
 * How the [SortedBytesMap] indexes of a heap dump store the object ids that key them: as
 * [byteCount] byte offsets from a base, the smallest object id in the dump.
 *
 * A key then costs what the *span* of a heap dump's object ids needs rather than what a whole id
 * needs. On a JVM heap dump that turns 8 byte ids into 4 byte keys, because a heap spans far less
 * than the 4 GB an unsigned int covers. An Android heap dump's ids are already 4 bytes, and ART
 * spreads its heaps over most of the range an int addresses, so there a key usually stays 4 bytes
 * and only a heap dump of a small heap gets down to 3 or fewer.
 *
 * Because the base is the smallest id, no offset ever wraps and [encode] is therefore monotonic:
 * stored keys sort in the same order as the ids they encode. That's what lets entries be sorted and
 * binary searched on their stored bytes, with no decoding on the way.
 */
internal class ObjectIdEncoding private constructor(
  private val base: Long,
  val byteCount: Int
) {

  /** The value stored in place of [objectId]. */
  fun encode(objectId: Long): Long = objectId - base

  /** The object id that [encode] turned into [encoded]. */
  fun decode(encoded: Long): Long = base + encoded

  /**
   * Reads the [byteCount] key bytes at [index] of [array], as stored, i.e. still encoded.
   *
   * The two widths a real heap dump lands on read as a whole int or long rather than through the
   * byte at a time [ByteArray.readTruncatedLong]. Binary searching an index calls this on every
   * probe, and the byte at a time loop measurably slowed heap analysis down.
   */
  fun encodedKeyAt(
    array: ByteArray,
    index: Int
  ): Long = when (byteCount) {
    4 -> array.readInt(index).toLong() and 0xffffffffL
    8 -> array.readLong(index)
    else -> array.readTruncatedLong(index, byteCount)
  }

  /** The object id of the key at [index] of [array]. */
  fun keyAt(
    array: ByteArray,
    index: Int
  ): Long = base + encodedKeyAt(array, index)

  companion object {

    /** Ids stored whole, over the 8 bytes any id fits in. */
    val WHOLE_IDS = ObjectIdEncoding(base = 0L, byteCount = 8)

    /**
     * Offsets from [minObjectId], over as many bytes as the distance up to [maxObjectId] needs.
     *
     * Falls back to [WHOLE_IDS] for a heap dump with no objects at all, and for one whose ids are
     * further apart than an offset can express, which would take a heap spanning more than half the
     * address space and so never happens.
     */
    fun of(
      minObjectId: Long,
      maxObjectId: Long
    ): ObjectIdEncoding {
      val span = maxObjectId - minObjectId
      return if (maxObjectId >= minObjectId && span >= 0) {
        ObjectIdEncoding(base = minObjectId, byteCount = max(1, byteSizeForUnsigned(span)))
      } else {
        WHOLE_IDS
      }
    }
  }
}
