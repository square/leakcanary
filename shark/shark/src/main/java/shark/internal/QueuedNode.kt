package shark.internal

import shark.GcRoot
import shark.LibraryLeakReferenceMatcher

/**
 * An object the path finder has reached and not explored yet, and how it was reached. One of these
 * per object in the breadth first traversal's frontier, which reaches millions of objects on a
 * large heap dump, so this holds as little as it can:
 *
 * - The object is identified by its [objectIndex] rather than by its object id, which is 4 bytes
 *   instead of 8 and also saves a lookup: the traversal dequeues a node with the positional
 *   [shark.HeapGraph.findObjectByIndex] rather than with [shark.HeapGraph.findObjectById], which
 *   would binary search for the id that was just searched for when the node was enqueued.
 * - The details of the reference that led here (its name, the class that declares it, the library
 *   leak pattern it matched) are not held at all. Only the nodes on a path that gets reported need
 *   them, and for those they're re-derived from [Child.parent] and [Child.referenceIndexInParent]
 *   by reading the parent's references again. Holding a lazy resolver per queued node instead cost
 *   as much again as the nodes themselves.
 *
 * [toReferencePathNode] turns a path of these into the [ReferencePathNode] path that leak traces
 * are built from.
 */
internal sealed class QueuedNode {

  /** The `objectIndex` of the object this node was reached at. */
  abstract val objectIndex: Int

  /** An object reached directly from a GC root, i.e. the start of a path. */
  class Root(
    val gcRoot: GcRoot,
    val matchedLibraryLeak: LibraryLeakReferenceMatcher?,
    override val objectIndex: Int,
  ) : QueuedNode()

  /** An object reached by following a reference from [parent]. */
  class Child(
    override val objectIndex: Int,
    val parent: QueuedNode,
    /**
     * Index of the reference that led from [parent] to this node, within the sequence of references
     * that the traversal's [shark.ReferenceReader] returns for the parent object. Reading those
     * references again and picking this one out is how the reference details are recovered, which
     * assumes that a [shark.ReferenceReader] returns the same references in the same order every
     * time it's asked for the same object.
     */
    val referenceIndexInParent: Int,
  ) : QueuedNode()
}
