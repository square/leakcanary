package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapInstance

/**
 * A [ReferenceReader] that first delegates expanding to [virtualRefReaders] in order until one
 * matches (or none), and then always proceeds with [fieldRefReader]. This means any
 * synthetic ref will be on the shortest path, but we still explore the entire data structure so
 * that we correctly track which objects have been visited and correctly compute dominators and
 * retained size.
 */
internal class ChainingInstanceReferenceReader(
  private val virtualRefReaders: List<VirtualInstanceReferenceReader>,
  private val fieldRefReader: FieldInstanceReferenceReader
) : ReferenceReader<HeapInstance> {

  override fun read(source: HeapInstance): Sequence<Reference> {
    val virtualRefs = expandVirtualRefs(source)
    // Note: always forwarding to fieldRefReader means we may navigate the structure twice
    // which increases IO reads. However this is a trade-of that allows virtualRef impls to
    // focus on a subset of references and more importantly it means we still get a proper
    // calculation of retained size as we don't skip any instance.
    val fieldRefs = fieldRefReader.read(source)
    return virtualRefs + fieldRefs
  }

  private fun expandVirtualRefs(instance: HeapInstance): Sequence<Reference> {
    for (expander in virtualRefReaders) {
      if (expander.matches(instance)) {
        return expander.read(instance)
      }
    }
    return emptySequence()
  }

  /**
   * Same as [ReferenceReader] but [read] is only invoked when [matches] returns
   * true. [matches] should return false if this [VirtualInstanceReferenceReader] implementation isn't
   * able to expand the provided instance, in which case [ChainingInstanceReferenceReader] will delegate
   * to the next [VirtualInstanceReferenceReader] implementation.
   */
  interface VirtualInstanceReferenceReader : ReferenceReader<HeapInstance> {
    fun matches(instance: HeapInstance): Boolean

    /**
     * May create a new InstanceExpander, depending on what's in the heap graph.
     * [OptionalFactory] implementations might return a different [ReferenceReader]
     * depending on which version of a class is present in the heap dump, or they might return null if
     * that class is missing.
     */
    fun interface OptionalFactory {
      fun create(graph: HeapGraph): VirtualInstanceReferenceReader?
    }
  }
}
