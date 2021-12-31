package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.HeapObject.HeapInstance

/*
 * TODO Support Vector, Android message, ThreadLocal$ThreadLocalMap$Entry
 * ConcurrentHashMap =>  good for jdk, what about harmony?
 * ThreadLocal$Values.table
 *
 * MessageQueue.mMessages => Message.callback, Message.obj
 *  => should emit Message objects instead of obj + callback
 */
/**
 * A [InstanceReferenceReader] that first delegates expanding to [virtualRefReaders] in order until one
 * matches (or none), and then always proceeds with [fieldRefReader]. This means any
 * synthetic ref will be on the shortest path, but we still explore the entire data structure so
 * that we correctly track which objects have been visited and correctly compute dominators and
 * retained size.
 */
class ChainingInstanceReferenceReader(
  private val virtualRefReaders: List<VirtualInstanceReferenceReader>,
  private val fieldRefReader: FieldInstanceReferenceReader
) : ReferenceReader<HeapInstance> {


  override fun read(source: HeapInstance): Sequence<Reference> {
    val fieldRefs = fieldRefReader.read(source)
    // If there are no field refs then we won't find any synthetic refs either.
    if (!fieldRefs.iterator().hasNext()) {
      return emptySequence()
    }
    val syntheticRefs = expandSyntheticRefs(source)
    return syntheticRefs + fieldRefs
  }

  private fun expandSyntheticRefs(instance: HeapInstance): Sequence<Reference> {
    for (expander in virtualRefReaders) {
      if (expander.matches(instance)) {
        return expander.read(instance)
      }
    }
    return emptySequence()
  }

  /**
   * Same as [InstanceReferenceReader] but [read] is only invoked when [matches] returns
   * true. [matches] should return false if this [VirtualInstanceReferenceReader] implementation isn't
   * able to expand the provided instance, in which case [ChainingInstanceReferenceReader] will delegate
   * to the next [VirtualInstanceReferenceReader] implementation.
   */
  interface VirtualInstanceReferenceReader : ReferenceReader<HeapInstance> {
    fun matches(instance: HeapInstance): Boolean

    /**
     * May create a new InstanceExpander, depending on what's in the heap graph.
     * [OptionalFactory] implementations might return a different [InstanceReferenceReader]
     * depending on which version of a class is present in the heap dump, or they might return null if
     * that class is missing.
     */
    fun interface OptionalFactory {
      fun create(graph: HeapGraph): VirtualInstanceReferenceReader?
    }
  }

  companion object {
    fun factory(expanderFactories: List<OptionalFactory>): ReferenceReader.Factory<HeapInstance> {
      return ReferenceReader.Factory { graph ->
        val optionalExpanders = expanderFactories.mapNotNull { it.create(graph) }
        val referenceMatchers: List<ReferenceMatcher> = TODO()
        ChainingInstanceReferenceReader(optionalExpanders, FieldInstanceReferenceReader(graph, referenceMatchers))
      }
    }
  }
}
