package shark

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.ShardedSortedBytesMap
import shark.internal.ShardedSortedBytesMap.Companion.pickShardCount
import shark.internal.ShardedSortedBytesMap.Companion.shardOf
import shark.internal.UnsortedByteEntries

class ShardedSortedBytesMapTest {

  @Test fun singleShardBehavesLikeSortedBytesMap() {
    val ids = listOf(42L, 0L, 3L, Long.MAX_VALUE)
    val map = buildSharded(shardCount = 1, ids = ids)

    assertThat(map.size).isEqualTo(ids.size)
    ids.forEach { assertThat(it in map).isTrue }
    assertThat(map[12345L]).isNull()
    assertThat(map.entrySequence().map { it.first }.toList())
      .containsExactly(0L, 3L, 42L, Long.MAX_VALUE)
  }

  @Test fun multipleShardsContainAllKeys() {
    val ids = (0L until 1000L).shuffled(Random(7L)).toList()
    val map = buildSharded(shardCount = 4, ids = ids)

    assertThat(map.size).isEqualTo(ids.size)
    ids.forEach {
      assertThat(it in map).`as`("key $it").isTrue
      assertThat(map[it]).isNotNull
    }
    assertThat(map[Long.MIN_VALUE]).isNull()
    assertThat(map[12345L]).isNull()
  }

  @Test fun indexOfRoundTripsThroughGetAtIndex() {
    val ids = (0L until 500L step 7L).toList()
    val map = buildSharded(shardCount = 8, ids = ids)

    ids.forEach { id ->
      val idx = map.indexOf(id)
      assertThat(idx).`as`("indexOf($id)").isGreaterThanOrEqualTo(0)
      assertThat(map.keyAt(idx)).`as`("keyAt(indexOf($id))").isEqualTo(id)
      assertThat(map.getAtIndex(idx).readByte()).`as`("value at $id").isEqualTo((id and 0xff).toByte())
    }
  }

  @Test fun indexOfReturnsNegativeForMissingKey() {
    val map = buildSharded(shardCount = 4, ids = listOf(10L, 20L, 30L))

    assertThat(map.indexOf(15L)).isLessThan(0)
    assertThat(map.indexOf(99L)).isLessThan(0)
  }

  @Test fun entrySequenceVisitsEveryEntry() {
    val ids = (0L until 200L).map { it * 13L }.shuffled(Random(1L))
    val map = buildSharded(shardCount = 4, ids = ids)

    val visited = map.entrySequence().map { it.first }.toList()
    assertThat(visited).hasSize(ids.size)
    assertThat(visited.toSet()).isEqualTo(ids.toSet())
  }

  @Test fun pickShardCountReturnsOneWhenUnderLimit() {
    assertThat(pickShardCount(entryCount = 1_000_000, bytesPerEntry = 20)).isEqualTo(1)
  }

  @Test fun pickShardCountGrowsToFitOversizedIndex() {
    // 2.5 GB predicted footprint at 1 GB per shard → needs 4 shards.
    val maxBytesPerShard = 1L shl 30
    val n = pickShardCount(entryCount = 125_000_000, bytesPerEntry = 20, maxBytesPerShard = maxBytesPerShard)
    assertThat(n).isEqualTo(4)
    assertThat(125_000_000L * 20 / n).isLessThanOrEqualTo(maxBytesPerShard)
  }

  @Test fun pickShardCountIsAlwaysPowerOfTwo() {
    val cases = listOf(
      Triple(1_000_000, 100, 1L shl 20),
      Triple(10_000_000, 50, 1L shl 28),
      Triple(200_000_000, 25, 1L shl 30),
    )
    cases.forEach { (count, perEntry, limit) ->
      val n = pickShardCount(count, perEntry, limit)
      assertThat(n and (n - 1)).`as`("n=$n is power of two").isZero
      assertThat(count.toLong() * perEntry / n)
        .`as`("$count*$perEntry / $n ≤ $limit").isLessThanOrEqualTo(limit)
    }
  }

  @Test fun shardOfDistributesAlignedPointers() {
    // Pointer-like ids: 8-byte aligned, ascending. Naive (id and mask) would put everything
    // in shard 0; the mixer must spread them.
    val mask = 7
    val counts = IntArray(8)
    var id = 0x7f00_0000L
    repeat(8_000) {
      counts[shardOf(id, mask)]++
      id += 8L
    }
    counts.forEach { assertThat(it).isGreaterThan(0) }
  }

  /** Builds a sharded map where each entry's value is a single byte: `(id and 0xff)`. */
  private fun buildSharded(shardCount: Int, ids: List<Long>): ShardedSortedBytesMap {
    val mask = shardCount - 1
    val shards = Array(shardCount) {
      UnsortedByteEntries(bytesPerValue = 1, longIdentifiers = true)
    }
    ids.forEach { id ->
      shards[shardOf(id, mask)].append(id).writeByte((id and 0xff).toByte())
    }
    return ShardedSortedBytesMap(Array(shardCount) { shards[it].moveToSortedMap() })
  }
}
