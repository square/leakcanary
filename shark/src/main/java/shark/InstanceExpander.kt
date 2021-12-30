package shark

import shark.HeapObject.HeapInstance

/**
 * TODO Support Vector, Android message, ThreadLocal$ThreadLocalMap$Entry
 * ConcurrentHashMap =>  single for open jdk & harmony, very similar to hashmap. could
 * also merge the 3.
 * ThreadLocal$Values.table
 *
 * MessageQueue.mMessages => Message.callback, Message.obj
 *  => should emit Message objects instead of obj + callback
 */
fun interface InstanceExpander {
  /**
   * Returns the list of non null outgoing references from [instance]. Outgoing refs
   * can be actual fields or they can be synthetic fields when simplifying known data
   * structures.
   *
   * The returned list is sorted by [HeapInstanceRef.name] in alphanumeric order to
   * ensure consistent graph traversal across heap dumps (fields: class structure can evolve,
   * synthesized maps: keys always in the same order).
   *
   * The returned list may contain several [HeapInstanceRef] with an identical
   * [HeapInstanceRef.objectId].
   */
  fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceRef>

  /**
   * Create a new InstanceExpander.
   */
  fun interface Factory {
    fun create(graph: HeapGraph): InstanceExpander
  }
}
