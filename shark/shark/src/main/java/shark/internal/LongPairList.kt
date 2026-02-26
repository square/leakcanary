package shark.internal

/**
 * A dynamic list of (first, second) long pairs backed by a single flat LongArray.
 * Pairs are stored at consecutive indices [i*2, i*2+1], giving better memory locality
 * than a list of two-element arrays.
 *
 * Individual entries can be [cleared][clearAt], which makes them invisible to [forEach]
 * and [forEachIndexed] without shrinking the backing array. Callers must ensure that 0L
 * is not a valid [first] value, as it is used as the cleared-entry sentinel.
 */
internal class LongPairList {
  private var data = LongArray(16) // initial capacity for 8 pairs
  var size = 0
    private set

  fun add(first: Long, second: Long) {
    if (size * 2 == data.size) {
      data = data.copyOf(data.size * 2)
    }
    data[size * 2] = first
    data[size * 2 + 1] = second
    size++
  }

  /** Marks the entry at [index] as cleared, making it invisible to [forEach] and [forEachIndexed]. */
  fun clearAt(index: Int) {
    data[index * 2] = 0L
  }

  inline fun forEach(action: (first: Long, second: Long) -> Unit) {
    val d = data
    val n = size
    for (i in 0 until n) {
      val base = i * 2
      val first = d[base]
      if (first != 0L) {
        action(first, d[base + 1])
      }
    }
  }

  inline fun forEachIndexed(action: (index: Int, first: Long, second: Long) -> Unit) {
    val d = data
    val n = size
    for (i in 0 until n) {
      val base = i * 2
      val first = d[base]
      if (first != 0L) {
        action(i, first, d[base + 1])
      }
    }
  }
}
