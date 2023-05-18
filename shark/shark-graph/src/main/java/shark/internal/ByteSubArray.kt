package shark.internal

/**
 * Provides read access to a sub part of a larger array.
 */
internal class ByteSubArray(
  private val array: ByteArray,
  private val rangeStart: Int,
  size: Int,
  private val longIdentifiers: Boolean
) {

  private val endInclusive = size - 1

  private var currentIndex = 0

  fun readByte(): Byte {
    val index = currentIndex
    currentIndex++
    require(index in 0..endInclusive) {
      "Index $index should be between 0 and $endInclusive"
    }
    return array[rangeStart + index]
  }

  fun readId(): Long {
    return if (longIdentifiers) {
      readLong()
    } else {
      readInt().toLong()
    }
  }

  fun readInt(): Int {
    val index = currentIndex
    currentIndex += 4
    require(index >= 0 && index <= endInclusive - 3) {
      "Index $index should be between 0 and ${endInclusive - 3}"
    }
    return array.readInt(rangeStart + index)
  }

  fun readTruncatedLong(byteCount: Int): Long {
    val index = currentIndex
    currentIndex += byteCount
    require(index >= 0 && index <= endInclusive - (byteCount - 1)) {
      "Index $index should be between 0 and ${endInclusive - (byteCount - 1)}"
    }
    var pos = rangeStart + index
    val array = array

    var value = 0L

    var shift = (byteCount - 1) * 8
    while (shift >= 8) {
      value = value or (array[pos++] and 0xffL shl shift)
      shift -= 8
    }
    value = value or (array[pos] and 0xffL)
    return value
  }

  fun readLong(): Long {
    val index = currentIndex
    currentIndex += 8
    require(index >= 0 && index <= endInclusive - 7) {
      "Index $index should be between 0 and ${endInclusive - 7}"
    }
    return array.readLong(rangeStart + index)
  }
}

internal fun ByteArray.readShort(index: Int): Short {
  var pos = index
  val array = this
  val valueAsInt = array[pos++] and 0xff shl 8 or (array[pos] and 0xff)
  return valueAsInt.toShort()
}

internal fun ByteArray.readInt(index: Int): Int {
  var pos = index
  val array = this
  return (array[pos++] and 0xff shl 24
    or (array[pos++] and 0xff shl 16)
    or (array[pos++] and 0xff shl 8)
    or (array[pos] and 0xff))
}

internal fun ByteArray.readLong(index: Int): Long {
  var pos = index
  val array = this
  return (array[pos++] and 0xffL shl 56
    or (array[pos++] and 0xffL shl 48)
    or (array[pos++] and 0xffL shl 40)
    or (array[pos++] and 0xffL shl 32)
    or (array[pos++] and 0xffL shl 24)
    or (array[pos++] and 0xffL shl 16)
    or (array[pos++] and 0xffL shl 8)
    or (array[pos] and 0xffL))
}

@Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
private inline infix fun Byte.and(other: Long): Long = toLong() and other

@Suppress("NOTHING_TO_INLINE") // Syntactic sugar.
private inline infix fun Byte.and(other: Int): Int = toInt() and other