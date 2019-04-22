package leakcanary

sealed class HeapValue {
  data class ObjectReference(val value: Long) : HeapValue() {
    val isNull
      get() = value == 0L
  }

  data class BooleanValue(val value: Boolean) : HeapValue()
  data class CharValue(val value: Char) : HeapValue()
  data class FloatValue(val value: Float) : HeapValue()
  data class DoubleValue(val value: Double) : HeapValue()
  data class ByteValue(val value: Byte) : HeapValue()
  data class ShortValue(val value: Short) : HeapValue()
  data class IntValue(val value: Int) : HeapValue()
  data class LongValue(val value: Long) : HeapValue()
}