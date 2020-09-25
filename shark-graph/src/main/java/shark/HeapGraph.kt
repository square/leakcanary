package shark

import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray

/**
 * Enables navigation through the heap graph of objects.
 */
interface HeapGraph {
  val identifierByteSize: Int

  /**
   * In memory store that can be used to store objects this [HeapGraph] instance.
   */
  val context: GraphContext

  val objectCount: Int

  val classCount: Int

  val instanceCount: Int

  val objectArrayCount: Int

  val primitiveArrayCount: Int

  /**
   * All GC roots which type matches types known to this heap graph and which point to non null
   * references. You can retrieve the object that a GC Root points to by calling [findObjectById]
   * with [GcRoot.id], however you need to first check that [objectExists] returns true because
   * GC roots can point to objects that don't exist in the heap dump.
   */
  val gcRoots: List<GcRoot>

  /**
   * Sequence of all objects in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val objects: Sequence<HeapObject>

  /**
   * Sequence of all classes in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val classes: Sequence<HeapClass>

  /**
   * Sequence of all instances in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val instances: Sequence<HeapInstance>

  /**
   * Sequence of all object arrays in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val objectArrays: Sequence<HeapObjectArray>

  /**
   * Sequence of all primitive arrays in the heap dump.
   *
   * This sequence does not trigger any IO reads.
   */
  val primitiveArrays: Sequence<HeapPrimitiveArray>

  /**
   * Returns the [HeapObject] corresponding to the provided [objectId], and throws
   * [IllegalArgumentException] otherwise.
   */
  @Throws(IllegalArgumentException::class)
  fun findObjectById(objectId: Long): HeapObject

  /**
   * Returns the [HeapObject] corresponding to the provided [objectIndex], and throws
   * [IllegalArgumentException] if [objectIndex] is less than 0 or more than [objectCount] - 1.
   */
  @Throws(IllegalArgumentException::class)
  fun findObjectByIndex(objectIndex: Int): HeapObject

  /**
   * Returns the [HeapObject] corresponding to the provided [objectId] or null if it cannot be
   * found.
   */
  fun findObjectByIdOrNull(objectId: Long): HeapObject?

  /**
   * Returns the [HeapClass] corresponding to the provided [className], or null if the
   * class cannot be found.
   */
  fun findClassByName(className: String): HeapClass?

  /**
   * Returns true if the provided [objectId] exists in the heap dump.
   */
  fun objectExists(objectId: Long): Boolean
}