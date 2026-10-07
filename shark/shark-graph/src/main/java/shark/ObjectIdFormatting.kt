package shark

private const val LOW_32_BITS = 0xFFFFFFFFL

fun Long.asObjectIdString(): String {
  val address = if (this < 0L) this and LOW_32_BITS else this
  return "$this (0x${address.toString(16)})"
}
