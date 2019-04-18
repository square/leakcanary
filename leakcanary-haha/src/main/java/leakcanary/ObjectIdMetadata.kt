package leakcanary

enum class ObjectIdMetadata {
  PRIMITIVE_WRAPPER,
  PRIMITIVE_WRAPPER_ARRAY,
  OBJECT_ARRAY,
  PRIMITIVE_ARRAY,
  STRING,
  INSTANCE,
  CLASS,
  ;

  val ordinalByte: Byte = ordinal.toByte()

  companion object {
    fun fromOrdinalByte(ordinalByte: Byte): ObjectIdMetadata = values()[ordinalByte.toInt()]
  }
}