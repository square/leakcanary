package shark

import shark.internal.packedWith
import shark.internal.unpackAsFirstInt
import shark.internal.unpackAsSecondInt

/**
 * Constructors can't be inlined so we used a function instead.
 */
inline fun Retained(
  /**
   * The minimum number of bytes which would be freed if all references to this object were
   * released. Should not exceed [Int.MAX_VALUE] bytes.
   */
  heapSize: ByteSize,

  /**
   * The minimum number of objects which would be unreachable if all references to this object were
   * released.
   */
  objectCount: Int,
) = Retained(heapSize.inWholeBytes.toInt() packedWith objectCount)

// DO NOT ADD A COMPANION OBJECT: a value class is supposed to be lightweight and its usage inlined
// into few instructions. After adding a companion object, call sites get a lot more instructions.
@JvmInline
value class Retained @PublishedApi internal constructor(
  @PublishedApi @JvmField
  internal val packedValue: Long
) {
  inline val heapSize: ByteSize
    get() = packedValue.unpackAsFirstInt.bytes

  inline val objectCount: Int
    get() = packedValue.unpackAsSecondInt

  inline val isUnknown: Boolean
    get() = this == UNKNOWN_RETAINED

  inline val isZero: Boolean
    get() = this == ZERO_RETAINED
}

val ZERO_RETAINED = Retained(ZERO_BYTES, 0)
val UNKNOWN_RETAINED = Retained((-1).bytes, -1)
