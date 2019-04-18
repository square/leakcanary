package leakcanary

import leakcanary.HprofParser.Companion.BITS_FOR_FILE_POSITION

enum class ObjectIdMetadata {
  PRIMITIVE_WRAPPER,
  PRIMITIVE_WRAPPER_ARRAY,
  OBJECT_ARRAY,
  PRIMITIVE_ARRAY,
  /**
   * An [INSTANCE] of the String class.
   */
  STRING,
  INSTANCE,
  CLASS,
  /**
   * An [INSTANCE] with 8 bytes or less of field values, which therefore has no fields that
   * could reference other instances.
   */
  SHALLOW_INSTANCE,
  ;

  fun packOrdinalWithFilePosition(filePosition: Long): Int {
    val shiftedOrdinal = ordinal shl BITS_FOR_FILE_POSITION
    return shiftedOrdinal or filePosition.toInt()
  }

  companion object {
    init {
      require(values().size <= 8) {
        "ObjectIdMetadata is packed as 3 bits in an int, it can only have up to 8 values, not ${values().size}"
      }
    }

    fun unpackMetadataAndPosition(packedInt: Int): Pair<ObjectIdMetadata, Long> {
      val unpackedOrdinal = (packedInt shr BITS_FOR_FILE_POSITION) and 7
      // 2^BITS_FOR_FILE_POSITION - 1
      val unpackedPosition = packedInt and 536870911

      return values()[unpackedOrdinal] to unpackedPosition.toLong()
    }
  }
}