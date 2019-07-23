package shark

import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance

/**
 * Enables navigation through the heap graph of objects.
 */
interface HeapGraph {
  val identifierByteSize: Int
  /**
   * In memory store that can be used to store objects this [HeapGraph] instance.
   */
  val context: GraphContext
  /**
   * All GC roots which type matches types known to this heap graph.
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
   * Returns the [HeapObject] corresponding to the provided [objectId], and throws
   * [IllegalArgumentException] otherwise.
   */
  @Throws(IllegalArgumentException::class)
  fun findObjectById(objectId: Long): HeapObject

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