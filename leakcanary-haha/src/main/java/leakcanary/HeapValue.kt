package leakcanary

import leakcanary.HeapValue.ObjectReference

/**
 * A value in the heap dump, which can be an [ObjectReference] or
 * a primitive type.
 */
sealed class HeapValue {
  data class ObjectReference(val value: Long) : HeapValue() {
    val isNull
      get() = value == NULL_REFERENCE
  }

  data class BooleanValue(val value: Boolean) : HeapValue()
  data class CharValue(val value: Char) : HeapValue()
  data class FloatValue(val value: Float) : HeapValue()
  data class DoubleValue(val value: Double) : HeapValue()
  data class ByteValue(val value: Byte) : HeapValue()
  data class ShortValue(val value: Short) : HeapValue()
  data class IntValue(val value: Int) : HeapValue()
  data class LongValue(val value: Long) : HeapValue()

  companion object {
    const val NULL_REFERENCE = 0L
  }
}