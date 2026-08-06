package shark

import shark.HeapObject.HeapInstance

/**
 * One entry of a map in a heap dump: the object the map really holds it in — a `HashMap$Node` and the
 * like — and the key and value it maps.
 *
 * Handed out by [MapEntryReader], for callers that need the node itself. A leak trace doesn't: it reads
 * a map as `map["key"]`, which is what [OpenJdkInstanceRefReaders] and [ApacheHarmonyInstanceRefReaders]
 * present, and the node it read that off is an implementation detail nobody wants in a trace.
 */
class HeapMapEntry(
  /** The object holding [key] and [value], one per entry an app put in the map. */
  val instance: HeapInstance,
  val key: HeapValue,
  val value: HeapValue
)
