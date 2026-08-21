package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.ObjectIdEncoding

class ObjectIdEncodingTest {

  @Test fun keyIsAsWideAsTheSpanOfTheIds() {
    assertThat(ObjectIdEncoding.of(42, 42).byteCount).`as`("a single id").isEqualTo(1)
    assertThat(ObjectIdEncoding.of(-3, 252).byteCount).`as`("255 apart").isEqualTo(1)
    assertThat(ObjectIdEncoding.of(-3, 253).byteCount).`as`("256 apart").isEqualTo(2)
    // An Android heap dump: 4 byte ids, sign extended, ART spreading its heaps over the range.
    assertThat(ObjectIdEncoding.of(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).byteCount)
      .`as`("the whole 4 byte id range").isEqualTo(4)
    // A JVM heap dump: 8 byte ids, but a heap that spans well under 4 GB of them.
    assertThat(ObjectIdEncoding.of(0x7f9c40000000L, 0x7f9c40000000L + 3_000_000_000L).byteCount)
      .`as`("3 GB of 8 byte ids").isEqualTo(4)
  }

  @Test fun everyIdInTheSpanRoundTrips() {
    val smallestId = -0x7f9c40000000L
    for (byteCount in 1..8) {
      val span = if (byteCount == 8) Long.MAX_VALUE else (1L shl (byteCount * 8)) - 1
      val encoding = ObjectIdEncoding.of(smallestId, smallestId + span)
      assertThat(encoding.byteCount).`as`("$byteCount byte keys").isEqualTo(byteCount)
      for (id in longArrayOf(smallestId, smallestId + span / 2, smallestId + span)) {
        assertThat(encoding.decode(encoding.encode(id))).`as`("$byteCount byte keys").isEqualTo(id)
      }
    }
  }

  /**
   * Entries are sorted and binary searched on their encoded keys without decoding, which only works
   * because encoding preserves the order of the ids.
   */
  @Test fun encodingPreservesTheOrderOfTheIds() {
    val ids = longArrayOf(Int.MIN_VALUE.toLong(), -1L, 0L, 1L, Int.MAX_VALUE.toLong())
    val encoding = ObjectIdEncoding.of(ids.first(), ids.last())
    val encoded = ids.map { encoding.encode(it) }
    assertThat(encoded).isSorted
    assertThat(encoded).allMatch { it >= 0 }
  }

  @Test fun idsAreStoredWholeWhenTheHeapDumpHasNoObjects() {
    // What the counters of a first pass that saw no object at all hold.
    val encoding = ObjectIdEncoding.of(Long.MAX_VALUE, Long.MIN_VALUE)

    assertThat(encoding.byteCount).isEqualTo(8)
    assertThat(encoding.encode(42)).isEqualTo(42)
  }

  @Test fun idsAreStoredWholeWhenTheirSpanCannotBeExpressed() {
    val encoding = ObjectIdEncoding.of(Long.MIN_VALUE, Long.MAX_VALUE)

    assertThat(encoding.byteCount).isEqualTo(8)
    assertThat(encoding.encode(Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE)
    assertThat(encoding.decode(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE)
  }
}
