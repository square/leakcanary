package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.HeapObject.HeapInstance
import shark.internal.InternalSharedHashMapReferenceReader

/**
 * Reads a map instance of a heap dump as the entries an app put in it, without deciding what to make of
 * them: [readEntriesOf] hands back the node holding each entry as well as its key and value, so that a
 * caller can say something about the node — how firmly it holds what it points at, say — rather than
 * only about what the map maps.
 *
 * That is the difference from [OpenJdkInstanceRefReaders] and [ApacheHarmonyInstanceRefReaders], which
 * read the same maps into references straight from the map to each key and value, the node left out.
 * Those are what a leak trace wants; both are the same walk over the same table, and this is the half of
 * it that has no opinion.
 *
 * Which map implementations a dump can have been written by is the question those readers already
 * answer — OpenJDK's `HashMap` and the Apache Harmony one Android shipped before it name their nodes and
 * their fields differently — so this is built out of them rather than beside them, and it covers exactly
 * the maps they cover: `HashMap`, `LinkedHashMap` and `ConcurrentHashMap`. A `WeakHashMap` is not one of
 * them, its entries being references in their own right, and neither is a set, which holds no values.
 */
class MapEntryReader private constructor(
  private val readers: List<InternalSharedHashMapReferenceReader>
) {

  /**
   * The entries of [map] in the order they sit in its table, or null when [map] is not a map this knows
   * how to read — which is every other object of a heap dump, and worth telling apart from a map with
   * nothing in it, which reads as no entries.
   */
  fun readEntriesOf(map: HeapInstance): Sequence<HeapMapEntry>? {
    val reader = readers.firstOrNull { it.matches(map) } ?: return null
    return reader.readEntries(map)
  }

  companion object {
    /**
     * Reads which map implementations [graph] was written by, once, the way [OpenJdkInstanceRefReaders]
     * and [ApacheHarmonyInstanceRefReaders] read it: each asks the graph for the classes and the fields
     * that tell one implementation from the other, and hands back nothing when the dump has neither.
     */
    fun createFor(graph: HeapGraph): MapEntryReader {
      val factories: List<OptionalFactory> =
        OpenJdkInstanceRefReaders.values().toList() + ApacheHarmonyInstanceRefReaders.values()
      val readers = factories
        .mapNotNull { factory -> factory.create(graph) }
        // The map readers of those two lists, which are the ones built out of the shared walk over a
        // table of nodes. What that leaves out is what has no entries to hand out: the set readers,
        // which build the same walk per read over the map inside the set, and the readers of the
        // structures that aren't maps at all.
        .filterIsInstance<InternalSharedHashMapReferenceReader>()
      return MapEntryReader(readers)
    }
  }
}
