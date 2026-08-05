package shark.explorer

import shark.AndroidReferenceReaders
import shark.ApacheHarmonyInstanceRefReaders
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.OpenJdkInstanceRefReaders
import shark.Reference

/**
 * What a data structure holds, as references straight from the structure to each entry — `list[3]`,
 * `map["key"]` — rather than through the array, the nodes and the entry objects it is really built from.
 *
 * Shark already knows how to read a dozen of them, and this is that list: [OpenJdkInstanceRefReaders] and
 * [ApacheHarmonyInstanceRefReaders] for the two `java.util` implementations an Android dump can have been
 * written by, and [AndroidReferenceReaders] for the framework's own. A path through a `HashMap` reads
 * `MainActivity.map → HashMap["key"]` here as it does in a LeakCanary leak trace, rather than
 * `MainActivity.map → HashMap.table → HashMap$HashMapEntry[] → [3] → HashMapEntry.value`, which is five
 * steps to say what one says and four objects an app doesn't have code for.
 *
 * **Additive**, exactly like [ViewChildReferenceReader] and for the same reason: the table, its entries
 * and its nodes are still reached through the fields they really live in and are still nodes holding
 * their own bytes, because the explorer needs every object of a heap dump to be a node exactly once (see
 * [ReferenceStrengthReader]). What takes them out of the middle of the tree is the dominator tree — both
 * ways to an entry now start at the structure, so the structure dominates it and its bookkeeping is left
 * retaining nothing but itself. Which is also what makes a treemap read as the sizes of what an app put
 * in its collections rather than as the sizes of their tables.
 *
 * All of them but one. [AndroidReferenceReaders.ANIMATOR_WEAK_REF_SUCKS] reads an `ObjectAnimator`'s
 * target through the `WeakReference` it is held in and presents it as a plain field, which is a guess
 * LeakCanary makes on purpose — the animation handler tends to keep that target alive — and it is the one
 * thing this explorer can't say. Here a weak reference holds nothing, the strength of what reaches an
 * object is a property the whole tree is built on, and an edge like that would make a weakly held object
 * read as strongly held. See [WeakeningAwareReferenceReader].
 */
internal class DataStructureReferenceReader(graph: HeapGraph) {

  /**
   * One reader per structure the heap dump has the classes for, in the order Shark tries them, since it
   * is the order that decides which one reads a class two of them match.
   */
  private val readers: List<VirtualInstanceReferenceReader> =
    (AndroidReferenceReaders.values().asList() - AndroidReferenceReaders.ANIMATOR_WEAK_REF_SUCKS +
      OpenJdkInstanceRefReaders.values() +
      ApacheHarmonyInstanceRefReaders.values())
      .mapNotNull { it.create(graph) }

  /** What [source] holds when it is one of those structures, and nothing at all for anything else. */
  fun entryReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapInstance) {
      return emptySequence()
    }
    // The first that matches, rather than every one that does: a reader reads the whole structure, so a
    // second one over the same instance would be the same entries again under different names.
    val reader = readers.firstOrNull { it.matches(source) } ?: return emptySequence()
    return reader.read(source)
  }
}
