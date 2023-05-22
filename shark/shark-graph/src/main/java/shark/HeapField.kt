package shark

import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray

/**
 * Represents a static field or an instance field.
 */
class HeapField(
  /**
   * The class this field was declared in.
   */
  val declaringClass: HeapClass,
  /**
   * Name of the field
   */
  val name: String,
  /**
   * Value of the field. Also see shorthands [valueAsClass], [valueAsInstance],
   * [valueAsObjectArray], [valueAsPrimitiveArray].
   */
  val value: HeapValue
) {

  /**
   * Return a [HeapClass] is [value] references a class, and null otherwise.
   */
  val valueAsClass: HeapClass?
    get() = value.asObject?.asClass

  /**
   * Return a [HeapInstance] is [value] references an instance, and null otherwise.
   */
  val valueAsInstance: HeapInstance?
    get() = value.asObject?.asInstance

  /**
   * Return a [HeapObjectArray] is [value] references an object array, and null otherwise.
   */
  val valueAsObjectArray: HeapObjectArray?
    get() = value.asObject?.asObjectArray

  /**
   * Return a [HeapPrimitiveArray] is [value] references a primitive array, and null
   * otherwise.
   */
  val valueAsPrimitiveArray: HeapPrimitiveArray?
    get() = value.asObject?.asPrimitiveArray
}