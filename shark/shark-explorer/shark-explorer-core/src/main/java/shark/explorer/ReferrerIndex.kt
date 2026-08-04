package shark.explorer

import androidx.collection.IntList
import androidx.collection.MutableIntList
import shark.HeapGraph
import shark.HeapObject
import shark.Reference
import shark.ReferenceReader

/**
 * Which objects point at each object of a heap dump, held in memory.
 *
 * A heap dump records a reference only in the direction it points, so answering "what holds this one"
 * means reading every object in the dump — seconds on a large one. Asking that per question is what made
 * the details panel wait; this reads the dump once and answers from memory afterwards, which is what turns
 * searching for the paths to an object into a graph walk rather than a pass over a file per step.
 *
 * Stored as one linked list per object: an int per object for the head of its list, and two ints per
 * reference. That's around 30 MB on a million object dump, where a map of lists per object would be
 * several hundred. Objects are named by [HeapObject.objectIndex] throughout for the same reason.
 */
internal class ReferrerIndex private constructor(
  private val graph: HeapGraph,
  /** The last edge pointing at each object, by object index, or [NO_EDGE] when nothing points at it. */
  private val lastEdgeByObjectIndex: IntArray,
  /**
   * Which object each edge comes from, by object index, with [Reference.isLowPriority] in the top bit.
   *
   * Packed rather than kept beside it because there are two ints per reference here and a large heap dump
   * has millions of them, and an object index can't use that bit: they run from 0 to
   * [HeapGraph.objectCount] - 1.
   */
  private val referrerByEdge: IntList,
  /** The edge before each edge in the list of the object it points at, or [NO_EDGE]. */
  private val previousEdgeByEdge: IntList
) {

  val objectCount: Int get() = lastEdgeByObjectIndex.size

  /** The object index of [objectId], or [NOT_AN_OBJECT] for an id the heap dump has no object for. */
  fun indexOf(objectId: Long): Int =
    graph.findObjectByIdOrNull(objectId)?.objectIndex ?: NOT_AN_OBJECT

  fun objectIdAt(objectIndex: Int): Long = graph.findObjectByIndex(objectIndex).objectId

  /**
   * Calls [block] with the object index of everything pointing at the object at [objectIndex], most
   * recently indexed first, once per referring object however many of its fields point at it, and with
   * whether every one of those references is a [Reference.isLowPriority] one.
   */
  fun forEachReferrer(
    objectIndex: Int,
    block: (Int, Boolean) -> Unit
  ) {
    var edge = lastEdgeByObjectIndex[objectIndex]
    var referrer = NOT_AN_OBJECT
    var isLowPriority = false
    while (edge != NO_EDGE) {
      val packed = referrerByEdge[edge]
      val edgeReferrer = packed and OBJECT_INDEX_MASK
      // Two fields of the same object pointing at it is one referrer, not two, and the edges of one
      // object are next to each other in the list because they were indexed in one go. It is only worth
      // putting off if all of them are: one field holding it plainly is a way of holding it plainly.
      if (edgeReferrer == referrer) {
        isLowPriority = isLowPriority && packed < 0
      } else {
        if (referrer != NOT_AN_OBJECT) {
          block(referrer, isLowPriority)
        }
        referrer = edgeReferrer
        isLowPriority = packed < 0
      }
      edge = previousEdgeByEdge[edge]
    }
    if (referrer != NOT_AN_OBJECT) {
      block(referrer, isLowPriority)
    }
  }

  companion object {
    private const val NO_EDGE = -1

    /** No object has this index: they run from 0 to [HeapGraph.objectCount] - 1. */
    const val NOT_AN_OBJECT = -1

    /** What is left of a packed edge once the low priority bit is taken off. See [referrerByEdge]. */
    private const val OBJECT_INDEX_MASK = Int.MAX_VALUE

    private const val LOW_PRIORITY_BIT = Int.MIN_VALUE

    /**
     * Reads every object of [graph] and indexes the references [referenceReader] reports, which has to be
     * the reader the dominator tree was built with: a path through a reference the tree ignored would
     * explain a retention the tree doesn't show.
     */
    fun buildFor(
      graph: HeapGraph,
      referenceReader: ReferenceReader<HeapObject>
    ): ReferrerIndex {
      val lastEdgeByObjectIndex = IntArray(graph.objectCount) { NO_EDGE }
      val referrerByEdge = MutableIntList(graph.objectCount)
      val previousEdgeByEdge = MutableIntList(graph.objectCount)
      graph.objects.forEach { source ->
        val referrerIndex = source.objectIndex
        referenceReader.read(source).forEach { reference ->
          val target = graph.findObjectByIdOrNull(reference.valueObjectId) ?: return@forEach
          val targetIndex = target.objectIndex
          referrerByEdge += if (reference.isLowPriority) {
            referrerIndex or LOW_PRIORITY_BIT
          } else {
            referrerIndex
          }
          previousEdgeByEdge += lastEdgeByObjectIndex[targetIndex]
          lastEdgeByObjectIndex[targetIndex] = referrerByEdge.size - 1
        }
      }
      // The lists grow by doubling, so what they over-allocated is worth handing back on a heap dump with
      // millions of references in it.
      referrerByEdge.trim()
      previousEdgeByEdge.trim()
      return ReferrerIndex(
        graph = graph,
        lastEdgeByObjectIndex = lastEdgeByObjectIndex,
        referrerByEdge = referrerByEdge,
        previousEdgeByEdge = previousEdgeByEdge
      )
    }
  }
}
