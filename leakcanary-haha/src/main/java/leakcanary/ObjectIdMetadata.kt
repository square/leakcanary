package leakcanary

import leakcanary.HprofParser.Companion.BITS_FOR_FILE_POSITION

enum class ObjectIdMetadata {
  PRIMITIVE_WRAPPER_OR_PRIMITIVE_ARRAY,
  PRIMITIVE_WRAPPER_ARRAY,
  OBJECT_ARRAY,
  /**
   * An [INSTANCE] of the String class.
   */
  STRING,
  INSTANCE,
  CLASS,
  /**
   * An [INSTANCE] with 0 bytes of field values, which therefore has no fields that
   * could reference other instances.
   */
  EMPTY_INSTANCE,
  /**
   * An [INSTANCE] with N + 4 bytes of field values, where N is the size of an object id.
   * Art updated the Object class with two fields: shadow$_klass_ (pointer to a class, N bytes) and
   * shadow$_monitor_ (Int, 4 bytes). As a empty instances still have at list N + 4 bytes of field
   * values. The size of the Object class fields is discovered after we've already parsed some
   * objects so we have to store this separately from [EMPTY_INSTANCE] and check later.
   */
  INTERNAL_MAYBE_EMPTY_INSTANCE
  ;

  fun packOrdinalWithFilePosition(filePosition: Long): Int {
    val shiftedOrdinal = ordinal shl BITS_FOR_FILE_POSITION
    return shiftedOrdinal or filePosition.toInt()
  }

  companion object {

    private const val POSITION_MASK = (1 shl BITS_FOR_FILE_POSITION) - 1
    private const val ORDINAL_MASK = 0x7

    init {
      require(values().size <= 8) {
        "ObjectIdMetadata is packed as 3 bits in an int, it can only have up to 8 values, not ${values().size}"
      }
    }

    fun unpackMetadataAndPosition(packedInt: Int): Pair<ObjectIdMetadata, Long> {
      val unpackedOrdinal = (packedInt shr BITS_FOR_FILE_POSITION) and ORDINAL_MASK
      val unpackedPosition = packedInt and POSITION_MASK
      return values()[unpackedOrdinal] to unpackedPosition.toLong()
    }
  }
}