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
 * a primitive type.
 */
class HeapValue(
  /**
   * The graph of objects in the heap, which you can use to navigate the heap.
   */
  val graph: HeapGraph,
  /**
   * Holds the actual value that this [HeapValue] represents.
   */
  val holder: ValueHolder
) {

  /**
   * This [HeapValue] as a [Boolean] if it represents one, or null otherwise.
   */
  val asBoolean: Boolean?
    get() = if (holder is BooleanHolder) holder.value else null

  /**
   * This [HeapValue] as a [Char] if it represents one, or null otherwise.
   */
  val asChar: Char?
    get() = if (holder is CharHolder) holder.value else null

  /**
   * This [HeapValue] as a [Float] if it represents one, or null otherwise.
   */
  val asFloat: Float?
    get() = if (holder is FloatHolder) holder.value else null

  /**
   * This [HeapValue] as a [Double] if it represents one, or null otherwise.
   */
  val asDouble: Double?
    get() = if (holder is DoubleHolder) holder.value else null

  /**
   * This [HeapValue] as a [Byte] if it represents one, or null otherwise.
   */
  val asByte: Byte?
    get() = if (holder is ByteHolder) holder.value else null

  /**
   * This [HeapValue] as a [Short] if it represents one, or null otherwise.
   */
  val asShort: Short?
    get() = if (holder is ShortHolder) holder.value else null

  /**
   * This [HeapValue] as an [Int] if it represents one, or null otherwise.
   */
  val asInt: Int?
    get() = if (holder is IntHolder) holder.value else null

  /**
   * This [HeapValue] as a [Long] if it represents one, or null otherwise.
   */
  val asLong: Long?
    get() = if (holder is LongHolder) holder.value else null

  /**
   * This [HeapValue] as a [Long] if it represents an object reference, or null otherwise.
   */
  val asObjectId: Long?
    get() = if (holder is ReferenceHolder) holder.value else null

  /**
   * This [HeapValue] as a [Long] if it represents a non null object reference, or null otherwise.
   */
  val asNonNullObjectId: Long?
    get() = if (holder is ReferenceHolder && !holder.isNull) holder.value else null

  /**
   * True is this [HeapValue] represents a null object reference, false otherwise.
   */
  val isNullReference: Boolean
    get() = holder is ReferenceHolder && holder.isNull

  /**
   * True is this [HeapValue] represents a non null object reference, false otherwise.
   */
  val isNonNullReference: Boolean
    get() = holder is ReferenceHolder && !holder.isNull

  /**
   * The [HeapObject] referenced by this [HeapValue] if it represents a non null object reference,
   * or null otherwise.
   */
  val asObject: HeapObject?
    get() {
      return if (holder is ReferenceHolder && !holder.isNull) {
        return graph.findObjectById(holder.value)
      } else {
        null
      }
    }

  /**
   * If this [HeapValue] if it represents a non null object reference to an instance of the
   * [String] class that exists in the heap dump, returns a [String] instance with content that
   * matches the string in the heap dump. Otherwise returns null.
   *
   * This may trigger IO reads.
   */
  fun readAsJavaString(): String? {
    if (holder is ReferenceHolder && !holder.isNull) {
      val heapObject = graph.findObjectByIdOrNull(holder.value)
      return heapObject?.asInstance?.readAsJavaString()
    }
    return null
  }
}
