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
class ObjectReporter internal constructor(val heapObject: HeapObject) {

  private val mutableLabels = mutableListOf<String>()

  private val mutableLeakingStatuses = mutableListOf<LeakNodeStatusAndReason>()
  private val mutableLikelyLeakingStatuses = mutableListOf<LeakNodeStatusAndReason>()
  private val mutableNotLeakingStatuses = mutableListOf<LeakNodeStatusAndReason>()

  /**
   * All labels added via [addLabel] for the [heapObject] instance.
   */
  val labels: List<String>
    get() = mutableLabels

  /**
   * All leaking insights added via [reportLikelyLeaking], [reportLeaking] and [reportNotLeaking]
   * for the [heapObject] instance.
   */
  val leakNodeStatuses: List<LeakNodeStatusAndReason>
    get() = mutableLeakingStatuses + mutableLikelyLeakingStatuses + mutableNotLeakingStatuses

  /**
   * All leaking insights added via [reportLeaking] for the [heapObject] instance.
   */
  val leakingStatuses: List<LeakNodeStatusAndReason>
    get() = mutableLeakingStatuses

  /**
   * Adds a label that will be visible on the corresponding node in the leak trace.
   */
  fun addLabel(label: String) {
    mutableLabels += label
  }

  /**
   * @see [reportLeaking]
   */
  fun reportLikelyLeaking(reason: String) {
    mutableLikelyLeakingStatuses += LeakNodeStatus.leaking(reason)
  }

  /**
   * Call this to let LeakCanary know that this instance was expected to be unreachable, ie that
   * it's leaking.
   *
   * Only call this method if you're 100% sure this instance is leaking, otherwise call
   * [reportLikelyLeaking]. The difference is that instances that are "likely leaking" are not
   * considered to be leaking instances on which LeakCanary should compute the leak trace.
   */
  fun reportLeaking(reason: String) {
    mutableLeakingStatuses += LeakNodeStatus.leaking(reason)
  }

  /**
   * Call this to let LeakCanary know that this instance was expected to be reachable.
   */
  fun reportNotLeaking(reason: String) {
    mutableNotLeakingStatuses += LeakNodeStatus.notLeaking(reason)
  }

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