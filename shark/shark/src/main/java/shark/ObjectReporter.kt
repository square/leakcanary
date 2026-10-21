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

  private val mutableSyntheticFields = mutableListOf<SyntheticField>()

  /**
   * The fields added with [addSyntheticField], in the order they were added. The leak trace prints
   * them above [labels].
   */
  val syntheticFields: List<SyntheticField>
    get() = mutableSyntheticFields

  /**
   * Sentences that will be visible on the corresponding [heapObject] in the leak trace. A value read
   * off the object goes in [addSyntheticField] instead.
   */
  val labels = linkedSetOf<String>()

  /**
   * Reasons for which this object should be gone and isn't, which make it
   * [LeakTraceObject.Verdict.STUCK].
   */
  val stuckReasons = mutableSetOf<String>()

  /**
   * Reasons for which this object is still needed and therefore legitimately in memory, which make
   * it [LeakTraceObject.Verdict.EXPECTED].
   */
  val expectedReasons = mutableSetOf<String>()

  /**
   * Reports [value] as a field of [heapObject] named [name], such as `Fragment.mTag`. [value] is
   * printed as is. See [SyntheticField].
   */
  fun addSyntheticField(
    name: String,
    value: String
  ) {
    mutableSyntheticFields += SyntheticField(name, value)
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
