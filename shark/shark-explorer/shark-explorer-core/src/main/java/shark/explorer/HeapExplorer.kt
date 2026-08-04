package shark.explorer

import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit.NANOSECONDS
import shark.AndroidObjectSizeCalculator
import shark.CancelSignal
import shark.CloseableHeapGraph
import shark.GcRoot
import shark.GcRootProvider
import shark.GcRootReference
import shark.HeapDominatorTree
import shark.HeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecordTag
import shark.MatchingGcRootProvider
import shark.SharkLog

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
  val tree: HeapDominatorTreemap,
  /** The device and process that wrote the heap dump, which is where its bitmaps still are. */
  val origin: HeapDumpOrigin
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
     *
     * [cancelSignal] stops this and **every later read of the heap dump**, because the graph is asked on
     * every record it reads and the tree keeps that graph. So it belongs to whoever owns the open heap
     * dump: a signal that only means "stop opening" would go on cancelling reads long after this
     * returned.
     */
    fun open(
      heapDumpFile: File,
      onProgress: (String) -> Unit = {},
      cancelSignal: CancelSignal = CancelSignal.NEVER
    ): HeapExplorer {
      SharkLog.d { "Opening heap dump $heapDumpFile, ${formatByteSize(heapDumpFile.length())}" }
      val startNanos = System.nanoTime()
      val steps = OpenSteps(onProgress)
      // Every GC root kind the hprof records, rather than HprofIndex.defaultIndexedGcRootTags():
      // those defaults drop the kinds that can't explain a leak, which on a real app dump is 180 K of
      // the 188 K roots — interned strings and the runtime's internals. An explorer has to say where
      // every object is held, so dropping a root kind means calling 45 K live objects garbage.
      val graph = steps.run("Indexing ${heapDumpFile.name}") {
        heapDumpFile.openHeapGraph(
          indexedGcRootTypes = HprofRecordTag.rootTags,
          cancelSignal = cancelSignal
        )
      }
      try {
        // Everything the explorer knows that the heap dump doesn't say itself, in one place and read by
        // both halves of the edge set: which references don't retain, and which ones are the one way an
        // object is held.
        val rules = ExplorerRules.DEFAULT
        val strengthReader = ReferenceStrengthReader(graph, rules)
        val ownerReferences = steps.run("Working out what owns what") {
          OwnerReferences.computeFor(graph, rules)
        }
        val reachability = steps.run("Working out what's reachable") {
          HeapReachability.computeFor(
            graph = graph,
            strengthReader = strengthReader,
            ownerReferences = ownerReferences,
            gcRootProvider = MatchingGcRootProvider(emptyList()),
            objectSizeCalculator = AndroidObjectSizeCalculator(graph)
          )
        }
        val gcRootProvider = TreeGcRootProvider(reachability)
        val tree = steps.run("Working out what retains what") {
          val dominatorTree = HeapDominatorTree.buildFor(
            graph = graph,
            referenceReader = WeakeningAwareReferenceReader(strengthReader, reachability, ownerReferences),
            gcRootProvider = gcRootProvider
          )
          HeapDominatorTreemap(
            graph = graph,
            reachability = reachability,
            strengthReader = strengthReader,
            ownerReferences = ownerReferences,
            gcRootProvider = gcRootProvider,
            dominatorTree = dominatorTree,
            nodes = dominatorTree.buildNodes(AndroidObjectSizeCalculator(graph))
          )
        }
        SharkLog.d {
          val sizes = reachability.sizes
          "Opened ${heapDumpFile.name} in ${millisSince(startNanos)} ms: " +
            "${formatObjectCount(sizes.totalObjectCount)}, ${formatByteSize(sizes.totalByteCount)}, " +
            "${formatByteSize(sizes.unreachableByteCount)} of it unreachable"
        }
        return HeapExplorer(
          heapDumpFile = heapDumpFile,
          graph = graph,
          reachability = reachability,
          tree = tree,
          origin = HeapDumpOrigin.readFrom(graph)
        )
      } catch (throwable: Throwable) {
        // Closing on the way out must not replace what went wrong with a failure to close, which is
        // what would be reported and is never the cause.
        try {
          graph.close()
        } catch (closeFailure: Throwable) {
          SharkLog.d(closeFailure) { "Failed to close $heapDumpFile after giving up on opening it" }
        }
        throw throwable
      }
    }
  }
}

/**
 * Runs the steps of opening a heap dump, telling the caller and the log which one is running and how
 * long it took.
 *
 * Which step a session was in is the first thing to ask of its log when opening a dump never finished:
 * a step logged as started and never as done is where the app was killed, or where it ran out of the
 * heap a large dump needs.
 */
private class OpenSteps(private val onProgress: (String) -> Unit) {

  fun <T> run(
    description: String,
    step: () -> T
  ): T {
    onProgress(description)
    SharkLog.d { description }
    val startNanos = System.nanoTime()
    return step().also {
      SharkLog.d { "$description: done in ${millisSince(startNanos)} ms" }
    }
  }
}

private fun millisSince(startNanos: Long): Long = NANOSECONDS.toMillis(System.nanoTime() - startNanos)

/**
 * Where the dominator tree hangs the heap dump off: the GC roots that explain why what they point at is
 * still in memory, plus a way into every piece of uncollected garbage.
 *
 * A dominator tree built from the GC roots alone covers the reachable heap only, and the garbage is
 * usually the part of a dump nobody has looked at. So the objects no other piece of garbage points at
 * are handed over as roots too — [GcRoot.Unreachable] is the hprof record for exactly that, an object
 * that is no root and that nothing reachable holds.
 *
 * Whatever a piece of garbage retains still nests under it, because being pointed at by another
 * unreachable object is what keeps an object off that list. See
 * [HeapReachability.unreachableRootObjectIds].
 *
 * The roots left out are the ones that don't explain anything: a local variable of a running method
 * pointing at an object a field also points at says nothing about what keeps that object in memory, and
 * would put it flat under the GC roots rather than under the field. Same rule as for a weak reference —
 * see [HeapReachability.isHeldThrough] — and the same reason it's safe: a root that is the only way to an
 * object is kept, because then nothing holds it more firmly.
 */
internal class TreeGcRootProvider(private val reachability: HeapReachability) : GcRootProvider {

  override fun provideGcRoots(graph: HeapGraph): Sequence<GcRootReference> =
    MatchingGcRootProvider(emptyList()).provideGcRoots(graph)
      .filter { reference ->
        reachability.isHeldThrough(reference.gcRoot.id, reference.gcRoot.reachabilityStrength())
      } + reachability.unreachableRootObjectIds.asSequence().map { objectId ->
      GcRootReference(
        gcRoot = GcRoot.Unreachable(objectId),
        isLowPriority = true,
        matchedLibraryLeak = null
      )
    }
}
