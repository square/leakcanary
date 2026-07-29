package shark.explorer

import java.io.Closeable
import java.io.File
import shark.AndroidObjectSizeCalculator
import shark.CloseableHeapGraph
import shark.GcRoot
import shark.GcRootProvider
import shark.GcRootReference
import shark.HeapDominatorTree
import shark.HeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.MatchingGcRootProvider

/**
 * A heap dump, open and indexed, with its reachability worked out and its dominator [tree] built.
 *
 * Keeps the heap dump open so that labels and details are read only for the objects the UI ends up
 * showing, which means every call on this and on the tree it hands out can do IO. Must be [close]d.
 */
class HeapExplorer private constructor(
  val heapDumpFile: File,
  private val graph: CloseableHeapGraph,
  private val reachability: HeapReachability,
  /** Every object of the heap dump, reachable or not, weighted by what it retains. */
  val tree: HeapDominatorTreemap
) : Closeable {

  /** How the objects of the heap dump split up by reachability. */
  val sizes: HeapSizes get() = reachability.sizes

  override fun close() {
    graph.close()
  }

  companion object {
    /**
     * Opens [heapDumpFile], indexes it, works out what's reachable and builds the dominator tree, which
     * takes seconds and a few hundred MB of heap on a large dump. [onProgress] is called with a
     * description of each step as it starts.
     */
    fun open(
      heapDumpFile: File,
      onProgress: (String) -> Unit = {}
    ): HeapExplorer {
      onProgress("Indexing ${heapDumpFile.name}")
      val graph = heapDumpFile.openHeapGraph()
      try {
        val strengthReader = ReferenceStrengthReader(graph)
        onProgress("Working out what's reachable")
        val reachability = HeapReachability.computeFor(
          graph = graph,
          strengthReader = strengthReader,
          gcRootProvider = MatchingGcRootProvider(emptyList()),
          objectSizeCalculator = AndroidObjectSizeCalculator(graph)
        )
        onProgress("Working out what retains what")
        val nodes = HeapDominatorTree.buildFor(
          graph = graph,
          referenceReader = WeakeningAwareReferenceReader(strengthReader, reachability),
          gcRootProvider = UncollectedGarbageGcRootProvider(reachability.unreachableRootObjectIds)
        ).buildNodes(AndroidObjectSizeCalculator(graph))
        val tree = HeapDominatorTreemap(graph, reachability, strengthReader, nodes)
        return HeapExplorer(heapDumpFile, graph, reachability, tree)
      } catch (throwable: Throwable) {
        graph.close()
        throw throwable
      }
    }
  }
}

/**
 * The heap dump's own GC roots, plus a way into every piece of uncollected garbage.
 *
 * A dominator tree built from the GC roots alone covers the reachable heap only, and the garbage is
 * usually the part of a dump nobody has looked at. So the objects no other piece of garbage points at
 * are handed over as roots too — [GcRoot.Unreachable] is the hprof record for exactly that, an object
 * that is no root and that nothing reachable holds.
 *
 * Whatever a piece of garbage retains still nests under it, because being pointed at by another
 * unreachable object is what keeps an object off this list. See
 * [HeapReachability.unreachableRootObjectIds].
 */
internal class UncollectedGarbageGcRootProvider(
  private val unreachableRootObjectIds: List<Long>
) : GcRootProvider {

  override fun provideGcRoots(graph: HeapGraph): Sequence<GcRootReference> =
    MatchingGcRootProvider(emptyList()).provideGcRoots(graph) +
      unreachableRootObjectIds.asSequence().map { objectId ->
        GcRootReference(
          gcRoot = GcRoot.Unreachable(objectId),
          isLowPriority = true,
          matchedLibraryLeak = null
        )
      }
}
