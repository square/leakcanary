package leakcanary

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

  val ordinalByte: Byte = ordinal.toByte()

  companion object {
    fun fromOrdinalByte(ordinalByte: Byte): ObjectIdMetadata = values()[ordinalByte.toInt()]
  }
}