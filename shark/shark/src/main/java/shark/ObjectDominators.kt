@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import java.io.Serializable

/**
 * Exposes a high level API to compute a dominator tree. This class
 * needs to be public to be used by other LeakCanary modules but is internal and
 * its API might change at any moment.
 *
 * Note that the exposed API is not optimized for speed, memory or IO.
 *
 * Eventually this capability should become part of the Shark public APIs, please
 * open an issue if you'd like to use this directly.
 */
class ObjectDominators {

  data class DominatorNode(
    val shallowSize: Long,
    /**
     * The sum of the shallow sizes of this object and every object it dominates. A [Long] because
     * the root of the tree retains the whole reachable heap, which goes past [Int.MAX_VALUE] on
     * heap dumps larger than 2 GB.
     */
    val retainedSize: Long,
    val retainedCount: Int,
    val dominatedObjectIds: List<Long>
  ) : Serializable

  /**
   * [ignoredRefs] are the references that don't keep their target alive, so they're applied to the
   * reference reader as well as to the GC roots. Leaving them out of the reader would follow
   * `WeakReference.referent` and the finalizer list links, which attributes memory as retained when
   * it isn't.
   */
  fun buildDominatorTree(
    graph: HeapGraph,
    ignoredRefs: List<IgnoredReferenceMatcher>
  ): Map<Long, DominatorNode> {
    return HeapDominatorTree.buildFor(
      graph = graph,
      referenceReader = ActualMatchingReferenceReaderFactory(
        referenceMatchers = ignoredRefs
      ).createFor(graph),
      gcRootProvider = MatchingGcRootProvider(ignoredRefs)
    ).buildNodes(AndroidObjectSizeCalculator(graph))
  }
}
