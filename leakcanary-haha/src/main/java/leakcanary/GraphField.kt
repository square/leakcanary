package leakcanary

import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord

/**
 * Represents a static field or an instance field.
 */
class GraphField(
  /**
   * The class this field was declared in.
   */
  val classRecord: GraphClassRecord,
  /**
   * Name of the field
   */
  val name: String,
  /**
   * Value of the field. Also see shorthands [valueAsClass], [valueAsInstance],
   * [valueAsObjectArray], [valueAsPrimitiveArray].
   */
  val value: GraphHeapValue
) {

  /**
   * Return a [GraphClassRecord] is [value] references a class, and null otherwise.
   */
  val valueAsClass: GraphClassRecord?
    get() = value.asObject?.asClass

  /**
   * Return a [GraphInstanceRecord] is [value] references an instance, and null otherwise.
   */
  val valueAsInstance: GraphInstanceRecord?
    get() = value.asObject?.asInstance

  /**
   * Return a [GraphObjectArrayRecord] is [value] references an object array, and null otherwise.
   */
  val valueAsObjectArray: GraphObjectArrayRecord?
    get() = value.asObject?.asObjectArray

  /**
   * Return a [GraphPrimitiveArrayRecord] is [value] references a primitive array, and null
   * otherwise.
   */
  val valueAsPrimitiveArray: GraphPrimitiveArrayRecord?
    get() = value.asObject?.asPrimitiveArray
}