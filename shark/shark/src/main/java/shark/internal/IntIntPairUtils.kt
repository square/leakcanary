package shark.internal

internal inline infix fun Int.packedWith(that: Int) =
  ((toLong()) shl 32) or (that.toLong() and 0xffffffffL)

internal inline val Long.unpackAsFirstInt: Int
  get() = (this shr 32).toInt()
internal inline val Long.unpackAsSecondInt: Int
  get() = (this and 0xFFFFFFFF).toInt()
