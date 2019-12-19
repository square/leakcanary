package shark

import shark.HeapObject.HeapInstance
import kotlin.reflect.KClass

/**
 * Enables [ObjectInspector] implementations to provide insights on [heapObject], which is
 * an object (class, instance or array) found in the heap.
 *
 * A given [ObjectReporter] only maps to one object in the heap, but is shared to many
 * [ObjectInspector] implementations and accumulates insights.
 */
class ObjectReporter constructor(val heapObject: HeapObject) {

  /**
   * Labels that will be visible on the corresponding [heapObject] in the leak trace.
   */
  val labels = linkedSetOf<String>()

  /**
   * Reasons for which this object is expected to be unreachable (ie it's leaking).
   */
  val leakingReasons = mutableSetOf<String>()

  /**
   * Deprecated, use leakingReasons instead.
   */
  @Deprecated(
      "Replace likelyLeakingReasons with leakingReasons",
      replaceWith = ReplaceWith(
          "leakingReasons"
      )
  )
  val likelyLeakingReasons
    get() = leakingReasons

  /**
   * Reasons for which this object is expected to be reachable (ie it's not leaking).
   */
  val notLeakingReasons = mutableSetOf<String>()

  /**
   * Runs [block] if [ObjectReporter.heapObject] is an instance of [expectedClass].
   */
  fun whenInstanceOf(
    expectedClass: KClass<out Any>,
    block: ObjectReporter.(HeapInstance) -> Unit
  ) {
    whenInstanceOf(expectedClass.java.name, block)
  }

  /**
   * Runs [block] if [ObjectReporter.heapObject] is an instance of [expectedClassName].
   */
  fun whenInstanceOf(
    expectedClassName: String,
    block: ObjectReporter.(HeapInstance) -> Unit
  ) {
    val heapObject = heapObject
    if (heapObject is HeapInstance && heapObject instanceOf expectedClassName) {
      block(heapObject)
    }
  }

}