package shark

import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.PagedUnsortedByteEntries
import shark.internal.SortedBytesMaps
import shark.internal.UnsortedByteEntries

class PagedSortedBytesMapTest {

  @Test fun sortsAndReadsAcrossManyPageLayouts() {
    val seed = 1234L
    for (longIdentifiers in booleanArrayOf(true, false)) {
      for (bytesPerValue in intArrayOf(1, 3, 7)) {
        for (entriesPerPage in intArrayOf(1, 2, 4, 8)) {
          for (size in intArrayOf(0, 1, 2, 5, 8, 9, 16, 17, 100)) {
            checkSortedMap(
              longIdentifiers = longIdentifiers,
              bytesPerValue = bytesPerValue,
              entriesPerPage = entriesPerPage,
              ids = distinctIds(size, longIdentifiers, Random(seed + size))
            )
          }
        }
      }
    }
  }

  @Test fun sortsLargeInputAcrossManyPages() {
    // 5000 entries with 8 entries per page => ~625 pages, exercising many cross page merges.
    checkSortedMap(
      longIdentifiers = true,
      bytesPerValue = 5,
      entriesPerPage = 8,
      ids = distinctIds(5000, longIdentifiers = true, random = Random(99))
    )
  }

  @Test fun singlePageMatchesArraySortedBytesMap() {
    // A paged map with one big page must behave exactly like the single array implementation.
    val random = Random(7)
    val bytesPerValue = 3
    val ids = distinctIds(500, longIdentifiers = true, random = random)

    val reference = UnsortedByteEntries(bytesPerValue = bytesPerValue, longIdentifiers = true)
    val paged = PagedUnsortedByteEntries(
      bytesPerValue = bytesPerValue, longIdentifiers = true, entryCount = ids.size,
      entriesPerPage = 1024
    )
    ids.forEach { id ->
      val refWriter = reference.append(id)
      val pagedWriter = paged.append(id)
      for (i in 0 until bytesPerValue) {
        val b = valueByte(id, i)
        refWriter.writeByte(b)
        pagedWriter.writeByte(b)
      }
    }
    val refMap = reference.moveToSortedMap()
    val pagedMap = paged.moveToSortedMap()

    assertThat(pagedMap.size).isEqualTo(refMap.size)
    for (index in 0 until refMap.size) {
      assertThat(pagedMap.keyAt(index)).isEqualTo(refMap.keyAt(index))
    }
    ids.forEach { id ->
      assertThat(pagedMap.indexOf(id)).isEqualTo(refMap.indexOf(id))
    }
  }

  @Test fun entriesPerPageIsPowerOfTwoWithinBudget() {
    for (bytesPerEntry in intArrayOf(5, 12, 23, 64, 257)) {
      val entriesPerPage = SortedBytesMaps.entriesPerPage(bytesPerEntry)
      assertThat(entriesPerPage and (entriesPerPage - 1))
        .`as`("entriesPerPage=$entriesPerPage is a power of two").isZero
      assertThat(entriesPerPage.toLong() * bytesPerEntry)
        .`as`("page stays within budget").isLessThanOrEqualTo(SortedBytesMaps.TARGET_BYTES_PER_PAGE)
    }
  }

  @Test fun newBuilderUsesSingleArrayUnderLimit() {
    val builder = SortedBytesMaps.newBuilder(
      bytesPerValue = 8, longIdentifiers = true, entryCount = 1_000
    )
    assertThat(builder).isInstanceOf(UnsortedByteEntries::class.java)
  }

  private fun checkSortedMap(
    longIdentifiers: Boolean,
    bytesPerValue: Int,
    entriesPerPage: Int,
    ids: List<Long>
  ) {
    val desc = "longIds=$longIdentifiers bpv=$bytesPerValue epp=$entriesPerPage n=${ids.size}"
    val builder = PagedUnsortedByteEntries(
      bytesPerValue = bytesPerValue,
      longIdentifiers = longIdentifiers,
      entryCount = ids.size,
      entriesPerPage = entriesPerPage
    )
    ids.forEach { id ->
      val writer = builder.append(id)
      for (i in 0 until bytesPerValue) {
        writer.writeByte(valueByte(id, i))
      }
    }
    val map = builder.moveToSortedMap()
    val expected = ids.sorted()

    assertThat(map.size).`as`(desc).isEqualTo(ids.size)

    // entrySequence yields entries in ascending id order with the right values.
    val sequence = map.entrySequence().toList()
    assertThat(sequence.map { it.first }).`as`("$desc entrySequence keys").isEqualTo(expected)
    sequence.forEach { pair ->
      val value = pair.second
      for (i in 0 until bytesPerValue) {
        assertThat(value.readByte()).`as`("$desc entrySequence value").isEqualTo(valueByte(pair.first, i))
      }
    }

    // Per key lookups round trip.
    ids.forEach { id ->
      assertThat(id in map).`as`("$desc contains $id").isTrue
      val index = map.indexOf(id)
      assertThat(index).`as`("$desc indexOf $id").isGreaterThanOrEqualTo(0)
      assertThat(map.keyAt(index)).`as`("$desc keyAt").isEqualTo(id)
      val value = map[id]!!
      for (i in 0 until bytesPerValue) {
        assertThat(value.readByte()).`as`("$desc get value $id").isEqualTo(valueByte(id, i))
      }
    }

    // A key that isn't present is reported absent.
    if (ids.isNotEmpty()) {
      val absent = expected.last().let { if (it == Long.MAX_VALUE) Long.MIN_VALUE else it + 1 }
      if (absent !in ids) {
        assertThat(absent in map).`as`("$desc absent").isFalse
        assertThat(map.indexOf(absent)).`as`("$desc indexOf absent").isLessThan(0)
        assertThat(map[absent]).`as`("$desc get absent").isNull()
      }
    }
  }

  private fun valueByte(id: Long, i: Int): Byte = (id * 31 + i).toByte()

  private fun distinctIds(size: Int, longIdentifiers: Boolean, random: Random): List<Long> {
    val ids = LinkedHashSet<Long>(size)
    while (ids.size < size) {
      ids += if (longIdentifiers) random.nextLong() else random.nextInt().toLong()
    }
    return ids.toList().shuffled(random)
  }
}
