package shark

import shark.HeapObject.HeapInstance

/**
 * A [ReferenceReader] that first delegates expanding to [virtualRefReaders] in order until one
 * matches (or none), and then always proceeds with [fieldRefReader]. This means any
 * synthetic ref will be on the shortest path, but we still explore the entire data structure so
 * that we correctly track which objects have been visited and correctly compute dominators and
 * retained size.
 */
class ChainingInstanceReferenceReader(
  private val virtualRefReaders: List<VirtualInstanceReferenceReader>,
  private val flatteningInstanceReader: FlatteningPartitionedInstanceReferenceReader?,
  private val fieldRefReader: FieldInstanceReferenceReader
) : ReferenceReader<HeapInstance> {

  override fun read(source: HeapInstance): Sequence<Reference> {
    val virtualRefReader = findMatchingVirtualReader(source)
    return if (virtualRefReader == null) {
      fieldRefReader.read(source)
    } else {
      if (flatteningInstanceReader != null && virtualRefReader.readsCutSet) {
        flatteningInstanceReader.read(virtualRefReader, source)
      } else {
        val virtualRefs = virtualRefReader.read(source)
        // Note: always forwarding to fieldRefReader means we may navigate the structure twice
        // which increases IO reads. However this is a trade-of that allows virtualRef impls to
        // focus on a subset of references and more importantly it means we still get a proper
        // calculation of retained size as we don't skip any instance.
        val fieldRefs = fieldRefReader.read(source)
        virtualRefs + fieldRefs
      }
    }
  }

  private fun findMatchingVirtualReader(instance: HeapInstance): VirtualInstanceReferenceReader? {
    for (expander in virtualRefReaders) {
      if (expander.matches(instance)) {
        return expander
      }
    }
    return null
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
     * https://en.wikipedia.org/wiki/Cut_(graph_theory)
     * A cut is a partition of the vertices of a graph into two disjoint subsets. Any cut
     * determines a cut-set, the set of edges that have one endpoint in each subset of the
     * partition. These edges are said to cross the cut.
     *
     * If true, the references returned by [read] will include the cut-set, which means any other
     * object reacheable from the source instance but not returned by [read] has no outgoing
     * edge to the rest of the graph. In other words, the internals of the data structure cannot
     * reach beyond the data structure itself.
     *
     * When this is true then [ChainingInstanceReferenceReader] can leverage
     * [FlatteningPartitionedInstanceReferenceReader].
     */
    val readsCutSet: Boolean

    /**
     * May create a new [VirtualInstanceReferenceReader], depending on what's in the heap graph.
     * [OptionalFactory] implementations might return a different [ReferenceReader]
     * depending on which version of a class is present in the heap dump, or they might return null if
     * that class is missing.
     */
    fun interface OptionalFactory {
      fun create(graph: HeapGraph): VirtualInstanceReferenceReader?
    }

    /**
     * Creates a list of [VirtualInstanceReferenceReader] where the content of the list depends on
     * the classes in the heap graph and their implementation. This is a chain as
     * [VirtualInstanceReferenceReader] elements in the list will process references in order in
     * [ChainingInstanceReferenceReader].
     */
    fun interface ChainFactory {
      fun createFor(graph: HeapGraph): List<VirtualInstanceReferenceReader>
    }
  }
}
