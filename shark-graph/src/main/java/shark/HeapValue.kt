package shark

import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder

/**
 * Represents a value in the heap dump, which can be an object reference or
 * a primitive type. Provides navigation capabilities.
 */
class HeapValue(
  private val graph: HeapGraph,
  val holder: ValueHolder
) {
  val asBoolean: Boolean?
    get() = if (holder is BooleanHolder) holder.value else null

  val asChar: Char?
    get() = if (holder is CharHolder) holder.value else null

  val asFloat: Float?
    get() = if (holder is FloatHolder) holder.value else null

  val asDouble: Double?
    get() = if (holder is DoubleHolder) holder.value else null

  val asByte: Byte?
    get() = if (holder is ByteHolder) holder.value else null

  val asShort: Short?
    get() = if (holder is ShortHolder) holder.value else null

  val asInt: Int?
    get() = if (holder is IntHolder) holder.value else null

  val asLong: Long?
    get() = if (holder is LongHolder) holder.value else null

  val asObjectId: Long?
    get() = if (holder is ReferenceHolder) holder.value else null

  val asNonNullObjectId: Long?
    get() = if (holder is ReferenceHolder && !holder.isNull) holder.value else null

  val isNullReference: Boolean
    get() = holder is ReferenceHolder && holder.isNull

  val isNonNullReference: Boolean
    get() = holder is ReferenceHolder && !holder.isNull

  val asObject: HeapObject?
    get() {
      return if (holder is ReferenceHolder && !holder.isNull) {
        return graph.findObjectById(holder.value)
      } else {
        null
      }
    }

  fun readAsJavaString(): String? {
    return asObject?.asInstance?.readAsJavaString()
  }
}
