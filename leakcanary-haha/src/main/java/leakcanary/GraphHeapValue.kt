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

class GraphHeapValue(
  private val graph: HprofGraph,
  val actual: HeapValue
) {
  val asBoolean: Boolean?
    get() = if (actual is BooleanValue) actual.value else null

  val asChar: Char?
    get() = if (actual is CharValue) actual.value else null

  val asFloat: Float?
    get() = if (actual is FloatValue) actual.value else null

  val asDouble: Double?
    get() = if (actual is DoubleValue) actual.value else null

  val asByte: Byte?
    get() = if (actual is ByteValue) actual.value else null

  val asShort: Short?
    get() = if (actual is ShortValue) actual.value else null

  val asInt: Int?
    get() = if (actual is IntValue) actual.value else null

  val asLong: Long?
    get() = if (actual is LongValue) actual.value else null

  val asObjectIdReference: Long?
    get() = if (actual is ObjectReference) actual.value else null

  val asNonNullObjectIdReference: Long?
    get() = if (actual is ObjectReference && !actual.isNull) actual.value else null

  val isNullReference: Boolean
    get() = actual is ObjectReference && actual.isNull

  val isNonNullReference: Boolean
    get() = actual is ObjectReference && !actual.isNull

  val asObject: GraphObjectRecord?
    get() {
      return if (actual is ObjectReference && !actual.isNull) {
        return graph.indexedObject(actual.value)
      } else {
        null
      }
    }

  fun readAsJavaString(): String? {
    return asObject?.asInstance?.readAsJavaString()
  }
}
