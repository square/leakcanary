package shark

import shark.HeapObject.HeapInstance

/**
 * A [InstanceExpander] that first delegates expanding to [syntheticExpanders] in order until one
 * matches (or none), and then always proceeds with [fieldInstanceExpander]. This means any
 * synthetic ref will be on the shortest path, but we still explore the entire data structure so
 * that we correctly track which objects have been visited and correctly compute dominators and
 * retained size.
 */
class ChainingInstanceExpander(
  private val syntheticExpanders: List<SyntheticInstanceExpander>,
  private val fieldInstanceExpander: FieldInstanceExpander
) : InstanceExpander {

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceRef> {
    val fieldRefs = fieldInstanceExpander.expandOutgoingRefs(instance)
    // If there are no field refs then we won't find any synthetic refs either.
    if (fieldRefs.isEmpty()) {
      return emptyList()
    }
    val syntheticRefs = expandSyntheticRefs(instance)
    // Micro optimization to avoid concatenating an empty list.
    return if (syntheticRefs.isEmpty()) {
      fieldRefs
    } else {
      return syntheticRefs + fieldRefs
    }
  }

  private fun expandSyntheticRefs(instance: HeapInstance): List<HeapInstanceRef> {
    for (expander in syntheticExpanders) {
      if (expander.matches(instance)) {
        return expander.expandOutgoingRefs(instance)
      }
    }
    return emptyList()
  }

  /**
   * Same as [InstanceExpander] but [expandOutgoingRefs] is only invoked when [matches] returns
   * true. [matches] should return false if this [SyntheticInstanceExpander] implementation isn't
   * able to expand the provided instance, in which case [ChainingInstanceExpander] will delegate
   * to the next [SyntheticInstanceExpander] implementation.
   */
  interface SyntheticInstanceExpander : InstanceExpander {
    fun matches(instance: HeapInstance): Boolean
  }

  /**
   * May create a new InstanceExpander, depending on what's in the heap graph.
   * [OptionalFactory] implementations might return a different [InstanceExpander]
   * depending on which version of a class is present in the heap dump, or they might return null if
   * that class is missing.
   */
  fun interface OptionalFactory {
    fun create(graph: HeapGraph): SyntheticInstanceExpander?
  }

  companion object {
    fun factory(expanderFactories: List<OptionalFactory>): InstanceExpander.Factory {
      return InstanceExpander.Factory { graph ->
        val optionalExpanders = expanderFactories.mapNotNull { it.create(graph) }
        ChainingInstanceExpander(optionalExpanders, FieldInstanceExpander(graph))
      }
    }
  }
}
