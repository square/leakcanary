package shark.internal.hppcshark

/** Alternative to Pair<Long, Object> that doesn't box long.*/
internal data class LongObjectPair<out B>(
  val first: Long,
  val second: B
)

/** Alternative to Pair<Long, Long> that doesn't box longs. */
internal data class LongLongPair(
  val first: Long,
  val second: Long
)

internal infix fun <B> Long.to(that: B): LongObjectPair<B> = LongObjectPair(this, that)

internal infix fun Long.to(that: Long): LongLongPair = LongLongPair(this, that)
