package shark.internal

import shark.internal.hppc.LongObjectPair

/**
 * A [SortedBytesMap] split across N power-of-two shards keyed by [shardOf].
 *
 * A single [SortedBytesMap] is backed by one [ByteArray], which the JVM caps at ~2 GB.
 * For very large heap dumps the per-record-type index can exceed that limit and the size
 * calculation overflows [Int], throwing [NegativeArraySizeException]. Sharding by id keeps
 * each backing array under the limit.
 *
 * The [Int] "global index" exposed via [indexOf], [getAtIndex], and [keyAt] is the
 * shard-by-shard concatenation: indices `[0, shards[0].size)` refer to shard 0, the next
 * block to shard 1, and so on. Within a shard, entries remain sorted by id; the global
 * concatenation is not sorted by id across shards. Callers that treat the index as an
 * opaque round-trip handle (e.g. `getAtIndex(indexOf(key))`) are unaffected.
 *
 * With a single shard, this is a thin pass-through over [SortedBytesMap].
 */
internal class ShardedSortedBytesMap(
  private val shards: Array<SortedBytesMap>
) {
  init {
    require(shards.isNotEmpty()) { "Must have at least one shard" }
    require(shards.size and (shards.size - 1) == 0) {
      "Shard count must be a power of two, was ${shards.size}"
    }
  }

  private val shardMask: Int = shards.size - 1

  // cumulativeSizes[i] = sum of shards[0 until i].size; cumulativeSizes[shards.size] = total.
  private val cumulativeSizes: IntArray = IntArray(shards.size + 1).also {
    for (i in shards.indices) {
      it[i + 1] = it[i] + shards[i].size
    }
  }

  val size: Int = cumulativeSizes[shards.size]

  operator fun get(key: Long): ByteSubArray? = shards[shardOf(key)][key]

  operator fun contains(key: Long): Boolean = key in shards[shardOf(key)]

  /**
   * Returns a non-negative global index if [key] is present, otherwise a negative number.
   * Unlike [SortedBytesMap.indexOf], the negative return value is not a meaningful
   * insertion point.
   */
  fun indexOf(key: Long): Int {
    val shard = shardOf(key)
    val local = shards[shard].indexOf(key)
    if (local < 0) return -1
    return cumulativeSizes[shard] + local
  }

  fun getAtIndex(keyIndex: Int): ByteSubArray {
    val shard = findShard(keyIndex)
    return shards[shard].getAtIndex(keyIndex - cumulativeSizes[shard])
  }

  fun keyAt(keyIndex: Int): Long {
    val shard = findShard(keyIndex)
    return shards[shard].keyAt(keyIndex - cumulativeSizes[shard])
  }

  fun entrySequence(): Sequence<LongObjectPair<ByteSubArray>> =
    shards.asSequence().flatMap { it.entrySequence() }

  private fun findShard(globalIndex: Int): Int {
    require(globalIndex in 0 until size) {
      "Index $globalIndex out of bounds [0, $size)"
    }
    // shards.size is small (typically 1, 2, 4, 8). A linear scan beats binary search here.
    for (i in shards.indices) {
      if (globalIndex < cumulativeSizes[i + 1]) return i
    }
    error("unreachable")
  }

  private fun shardOf(id: Long): Int = shardOf(id, shardMask)

  companion object {
    /**
     * Smallest power-of-two `n` such that `entryCount * bytesPerEntry / n <= maxBytesPerShard`.
     * Returns 1 when the predicted footprint already fits.
     */
    fun pickShardCount(
      entryCount: Int,
      bytesPerEntry: Int,
      maxBytesPerShard: Long = DEFAULT_MAX_BYTES_PER_SHARD
    ): Int {
      val predicted = entryCount.toLong() * bytesPerEntry
      var n = 1
      while (predicted / n > maxBytesPerShard) n = n shl 1
      return n
    }

    /**
     * Routes an id to a shard. Mixes the upper half of the long into the lower half before
     * masking so that pointer-like ids (often 8-byte aligned, low bits zero) still distribute
     * across shards. Must match the routing used by the writer.
     */
    fun shardOf(id: Long, shardMask: Int): Int =
      (((id ushr 3) xor (id ushr 32)).toInt()) and shardMask

    /**
     * 1 GB, well under the JVM's [Int.MAX_VALUE] byte-array ceiling so a shard has room
     * to grow before [UnsortedByteEntries] doubles its backing array.
     */
    const val DEFAULT_MAX_BYTES_PER_SHARD: Long = 1L shl 30
  }
}
