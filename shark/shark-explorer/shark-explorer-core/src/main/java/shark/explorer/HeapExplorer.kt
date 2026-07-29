package shark.explorer

import java.io.Closeable
import java.io.File
import shark.AndroidObjectSizeCalculator
import shark.CloseableHeapGraph
import shark.HeapDominatorTree
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.MatchingGcRootProvider

/**
 * A heap dump, open and indexed, with its reachability worked out.
 *
 * Keeps the heap dump open so that labels and details are read only for the objects the UI ends up
 * showing, which means every call on this and on the trees it hands out can do IO. It is not thread
 * safe: [shark.HprofHeapGraph] has one read cursor and one cache, so confine an instance to a single
 * thread, and not the one drawing the UI. Must be [close]d.
 */
class HeapExplorer private constructor(
  val heapDumpFile: File,
  private val graph: CloseableHeapGraph,
  private val strengthReader: ReferenceStrengthReader,
  private val reachability: HeapReachability
) : Closeable {

  /** How the bytes of the heap dump split up by reachability. */
  val sizes: HeapSizes get() = reachability.sizes

  private var cachedTree: HeapDominatorTreemap? = null

  /**
   * The dominator tree of the objects reachable by following the references that retain their target
   * plus the referents of the references in [followedStrengths].
   *
   * Takes seconds and hundreds of MB on a large heap dump, so the last result is kept: toggling a
   * strength off and back on is free, changing to a combination not seen before is not. [onProgress]
   * is called with a description of each step as it starts.
   */
  fun treeFor(
    followedStrengths: Set<ReachabilityStrength>,
    onProgress: (String) -> Unit = {}
  ): HeapDominatorTreemap {
    // Strong references are always followed, so asking for them says nothing and mustn't count as a
    // different tree.
    val followed = followedStrengths - ReachabilityStrength.STRONG
    cachedTree?.let { cached ->
      if (cached.followedStrengths == followed) {
        return cached
      }
    }
    onProgress(progressMessage(followed))
    // Dropped before building, so that two trees are never held at once on a heap dump big enough
    // for that to matter.
    cachedTree = null
    val nodes = HeapDominatorTree.buildFor(
      graph = graph,
      referenceReader = StrengthFilteringReferenceReader(
        strengthReader = strengthReader,
        reachability = reachability,
        followedStrengths = followed
      ),
      gcRootProvider = MatchingGcRootProvider(emptyList())
    ).buildNodes(AndroidObjectSizeCalculator(graph))
    return HeapDominatorTreemap(graph, reachability, strengthReader, nodes, followed)
      .also { cachedTree = it }
  }

  override fun close() {
    cachedTree = null
    graph.close()
  }

  companion object {
    /**
     * Opens [heapDumpFile], indexes it and works out what's reachable, which takes tens of seconds
     * and a few hundred MB of heap on a large dump. [onProgress] is called with a description of each
     * step as it starts.
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
        return HeapExplorer(heapDumpFile, graph, strengthReader, reachability)
      } catch (throwable: Throwable) {
        graph.close()
        throw throwable
      }
    }

    private fun progressMessage(followedStrengths: Set<ReachabilityStrength>): String {
      val included = if (followedStrengths.isEmpty()) {
        "strong references"
      } else {
        "strong and " + followedStrengths.sorted()
          .joinToString(", ") { it.name.lowercase() } + " references"
      }
      return "Computing the dominator tree of $included"
    }
  }
}
