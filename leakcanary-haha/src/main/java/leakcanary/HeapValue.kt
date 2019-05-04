package leakcanary

import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue

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

val HeapValue?.isNullReference
  get() = this is ObjectReference && isNull

val HeapValue?.reference
  get() = if (this is ObjectReference && !isNull) {
    this.value
  } else
    null

val HeapValue?.boolean
  get() = if (this is BooleanValue) {
    this.value
  } else
    null

val HeapValue?.char
  get() = if (this is CharValue) {
    this.value
  } else
    null

val HeapValue?.float
  get() = if (this is FloatValue) {
    this.value
  } else
    null

val HeapValue?.double
  get() = if (this is DoubleValue) {
    this.value
  } else
    null

val HeapValue?.byte
  get() = if (this is ByteValue) {
    this.value
  } else
    null

val HeapValue?.short
  get() = if (this is ShortValue) {
    this.value
  } else
    null

val HeapValue?.int
  get() = if (this is IntValue) {
    this.value
  } else
    null

val HeapValue?.long
  get() = if (this is LongValue) {
    this.value
  } else
    null