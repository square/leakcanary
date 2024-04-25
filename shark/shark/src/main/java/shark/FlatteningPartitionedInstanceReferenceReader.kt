@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.internal.hppc.LongScatterSet

/**
 * [FlatteningPartitionedInstanceReferenceReader] provides a synthetic and stable representation of
 * a data structure that maps how we think about that data structure instead of how it is internally
 * implemented. You can think of it as surfacing additional direct references to
 * entries that the data structure holds. [VirtualInstanceReferenceReader] implementations
 * scan references based on known patterns rather than through generic traversals. As a result,
 * they do not surface references and objects that are part of the data structure implementations,
 * such as internal arrays or linked lists. This is a problem because the same traversal is also
 * used to compute retained size, so we need to accounts for all reachable objects.
 *
 * One possible solution is to feed an instance to a [FieldInstanceReferenceReader] after its
 * already been processed by a [VirtualInstanceReferenceReader]. The [FieldInstanceReferenceReader]
 * will surface internal objects actually referenced by the source instance and from there the
 * internals will be fed back into the traversal. This has two downsides: first, we will re read
 * the exact same objects significantly later (with a DFS newly discovered objects are put back
 * to the end of the queue) by which time the objects are likely evicted from the cache and need to
 * be read again (additional IO). Second, when doing heap diffs to surface objects that grow, the
 * internals of data structures (arrays, linked list) will grow somewhere further down the path
 * from the data structure itself, so on top of the data structure already being surfaced as a
 * growing objects, the internal objects will also be surfaced as a distinct location of object
 * growth, creating noise in the result.
 *
 * [FlatteningPartitionedInstanceReferenceReader] exists to fix both of the issues mentioned in the
 * previous paragraph. It performs a local graph traversal and returns all internal objects
 * directly and indirectly dominated by a data structure as if they were all direct child of that
 * data structure, removing the need for a an additional processing step with
 * [FieldInstanceReferenceReader]. Because the graph traversal is local, with a dedicated small
 * queue, we benefit from the in memory cache and avoid double IO reads of objects. And because
 * these internal objects are all surfaced as direct children of the source instance, they'll
 * never appear to grow, removing noise in the result.
 *
 * [FlatteningPartitionedInstanceReferenceReader] wraps a [VirtualInstanceReferenceReader] itself
 * dedicated to a data structure that has no out edges beyond the one returned by the
 * [VirtualInstanceReferenceReader]. Once the [VirtualInstanceReferenceReader] is done emitting all
 * the out edges it knows about, [FlatteningPartitionedInstanceReferenceReader] will then explore
 * instances and object arrays in the rest of the local graph using [instanceReferenceReader] and
 * [objectArrayReferenceReader],
 * starting from the source, and emit all found nodes as virtual direct children of source.
 * [FlatteningPartitionedInstanceReferenceReader] communicates to its consumers that the inner nodes
 * should not be reloaded and explored by setting [Reference.isLeafObject] to true.
 *
 * Note: [FlatteningPartitionedInstanceReferenceReader] should only be used together with a
 * [VirtualInstanceReferenceReader] that identifies all inner out edges of the data structure,
 * as [FlatteningPartitionedInstanceReferenceReader] keeps track of those edges and knows to not
 * follow them. If we missed an out edge, the inner traversal would then keep going and end up
 * traversing the rest of the graph and presenting the entirety of the rest of the graph as
 * directly referenced by the source instance. [VirtualInstanceReferenceReader] that can be used
 * with [FlatteningPartitionedInstanceReferenceReader] return true from
 * [VirtualInstanceReferenceReader.readsCutSet].
 *
 * [FlatteningPartitionedInstanceReferenceReader] makes the assumption that there's no need to
 * explore any class found as those would have already be found through classloaders.
 *
 * A side effect of the flattening is that a path involving indirect internal objects will look a
 * bit strange, as the class for the owner of the reference will still be the real one, but
 * the reference will be directly attached to the data structure which doesn't have that class
 * in its class hierarchy.
 */
class FlatteningPartitionedInstanceReferenceReader(
  private val graph: HeapGraph,
  private val instanceReferenceReader: FieldInstanceReferenceReader,
) {
  private val objectArrayReferenceReader = ObjectArrayReferenceReader()

  private val visited = LongScatterSet()

  fun read(
    virtualInstanceReader: VirtualInstanceReferenceReader,
    source: HeapInstance
  ): Sequence<Reference> {
    visited.clear()
    val toVisit = mutableListOf<Reference>()
    visited += source.objectId

    val sourceTrackingSequence = virtualInstanceReader.read(source).map { reference ->
      visited += reference.valueObjectId
      reference
    }
    var startedTraversing = false

    val traversingSequence = generateSequence {
      if (!startedTraversing) {
        startedTraversing = true
        toVisit.enqueueNewReferenceVisit(instanceReferenceReader.read(source), visited)
      }
      val nextReference = toVisit.removeFirstOrNull() ?: return@generateSequence null

      val childReferences =
        when (val nextObject = graph.findObjectById(nextReference.valueObjectId)) {
          is HeapInstance -> instanceReferenceReader.read(nextObject)
          is HeapObjectArray -> objectArrayReferenceReader.read(nextObject)
          // We're assuming that classes should be reached through other nodes. Reaching a class
          // here first would be bad as it opens us up to traversing the entire graph, vs the local
          // finite traversal we want. This should be fine on Android, but could be different on
          // JVMs.
          is HeapClass -> emptySequence()
          is HeapPrimitiveArray -> emptySequence()
        }
      toVisit.enqueueNewReferenceVisit(childReferences, visited)
      nextReference.copy(isLeafObject = true)
    }
    return sourceTrackingSequence + traversingSequence
  }

  private fun MutableList<Reference>.enqueueNewReferenceVisit(
    references: Sequence<Reference>,
    visited: LongScatterSet
  ) {
    references.forEach { reference ->
      val added = visited.add(reference.valueObjectId)
      if (added) {
        this += reference
      }
    }
  }
}
