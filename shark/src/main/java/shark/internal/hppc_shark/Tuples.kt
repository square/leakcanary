package shark.internal.hppc_shark

/**
 * Replacement to a traditional Pair<Long, Long> that doesn't box long.
 * Class is purposefully not made as a `data class` to decrease memory footprint of the object.
 */
internal class LongLongPair(
  val first: Long,
  val second: Long
) {

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as LongLongPair

    if (first != other.first) return false
    if (second != other.second) return false

    return true
  }

  override fun hashCode(): Int {
    var result = first.hashCode()
    result = 31 * result + second.hashCode()
    return result
  }

  override fun toString(): String {
    return "LongLongPair(first=$first, second=$second)"
  }
}

internal infix fun Long.to(that: Long): LongLongPair =
  LongLongPair(this, that)
