package shark.internal.hppc_shark

/** Alternative to Pair<Long, Long> that doesn't box longs. */
internal data class LongLongPair(
  val first: Long,
  val second: Long
)

internal infix fun Long.to(that: Long): LongLongPair = LongLongPair(this, that)
