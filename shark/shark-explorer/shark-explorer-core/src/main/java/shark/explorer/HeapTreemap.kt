package shark.explorer

import java.io.Closeable
import java.io.File
import shark.AndroidObjectInspectors
import shark.AndroidObjectSizeCalculator
import shark.AndroidReferenceReaderFactory
import shark.CloseableHeapGraph
import shark.HeapDominatorTree
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.MatchingGcRootProvider
import shark.ObjectDominators.DominatorNode
import shark.ObjectReporter
import shark.ValueHolder.Companion.NULL_REFERENCE

/**
 * A heap dump's dominator tree, seen as a [TreemapTree] weighted by retained size.
 *
 * Nodes are object ids, and the root is [NULL_REFERENCE]: the virtual root [HeapDominatorTree] puts
 * above every GC root, so that the whole reachable heap is one rectangle.
 *
 * Keeps the heap dump open, so that labels and details are read only for the objects the UI ends up
 * showing. Must be [close]d.
 */
class HeapTreemap private constructor(
  val heapDumpFile: File,
  private val graph: CloseableHeapGraph,
  private val dominatorTree: Map<Long, DominatorNode>
) : TreemapTree<Long>, Closeable {

  override val root: Long get() = NULL_REFERENCE

  /** Bytes retained by [node]: its own shallow size plus that of everything it dominates. */
  override fun weight(node: Long): Long = dominatorTree.getValue(node).retainedSize.toLong()

  override fun children(node: Long): List<Long> = dominatorTree.getValue(node).dominatedObjectIds

  /**
   * A short name for [objectId], to draw on its rectangle.
   *
   * Cheap enough to call for every visible rectangle, unlike [summarize].
   */
  fun label(objectId: Long): String = if (objectId == root) {
    ROOT_LABEL
  } else {
    when (val heapObject = graph.findObjectById(objectId)) {
      is HeapClass -> "class ${heapObject.simpleName}"
      is HeapInstance -> heapObject.instanceClassSimpleName
      is HeapObjectArray -> heapObject.arrayClassSimpleName
      is HeapPrimitiveArray -> heapObject.arrayClassName
    }
  }

  /**
   * Everything the details panel shows about [objectId].
   *
   * Reads the object and runs Shark's object inspectors over it, so call it for the selected
   * object rather than for every rectangle.
   */
  fun summarize(objectId: Long): HeapObjectSummary {
    val node = dominatorTree.getValue(objectId)
    val heapObject = if (objectId == root) null else graph.findObjectById(objectId)
    return HeapObjectSummary(
      objectId = objectId,
      label = label(objectId),
      className = when (heapObject) {
        null -> ROOT_LABEL
        is HeapClass -> heapObject.name
        is HeapInstance -> heapObject.instanceClassName
        is HeapObjectArray -> heapObject.arrayClassName
        is HeapPrimitiveArray -> heapObject.arrayClassName
      },
      shallowSize = node.shallowSize,
      retainedSize = node.retainedSize.toLong(),
      retainedCount = node.retainedCount,
      dominatedObjectCount = node.dominatedObjectIds.size,
      inspectorLabels = if (heapObject == null) {
        emptyList()
      } else {
        val reporter = ObjectReporter(heapObject)
        AndroidObjectInspectors.appDefaults.forEach { it.inspect(reporter) }
        reporter.labels.toList()
      },
      stringValue = (heapObject as? HeapInstance)?.readAsJavaString()
    )
  }

  override fun close() {
    graph.close()
  }

  companion object {
    /** What the virtual root above every GC root is called in the UI. */
    const val ROOT_LABEL = "All GC roots"

    /**
     * Opens [heapDumpFile] and computes its dominator tree, which takes tens of seconds and a few
     * hundred MB of heap on a large dump, so don't call this on a UI thread. [onProgress] is called
     * with a description of each step as it starts.
     */
    fun open(
      heapDumpFile: File,
      onProgress: (String) -> Unit = {}
    ): HeapTreemap {
      onProgress("Indexing ${heapDumpFile.name}")
      val graph = heapDumpFile.openHeapGraph()
      val dominatorTree = try {
        onProgress("Computing the dominator tree")
        HeapDominatorTree.buildFor(
          graph = graph,
          // The Android reference readers, so that the graph matches what LeakCanary itself walks.
          // No reference matchers though: an explorer that ignored references would hide retained
          // memory, which is the one thing it exists to show.
          referenceReader = AndroidReferenceReaderFactory(emptyList()).createFor(graph),
          gcRootProvider = MatchingGcRootProvider(emptyList())
        ).buildNodes(AndroidObjectSizeCalculator(graph))
      } catch (throwable: Throwable) {
        graph.close()
        throw throwable
      }
      return HeapTreemap(heapDumpFile, graph, dominatorTree)
    }
  }
}

/** What the UI knows about one heap object. See [HeapTreemap.summarize]. */
data class HeapObjectSummary(
  val objectId: Long,
  /** Short name, as drawn on a rectangle. */
  val label: String,
  /** Fully qualified class name, or array type. */
  val className: String,
  val shallowSize: Int,
  val retainedSize: Long,
  /** Number of objects retained, including this one. */
  val retainedCount: Int,
  /** Number of objects immediately dominated by this one, ie its children in the treemap. */
  val dominatedObjectCount: Int,
  /** What Shark's object inspectors have to say, e.g. that an activity is destroyed. */
  val inspectorLabels: List<String>,
  /** The content of a `java.lang.String` instance, null for anything else. */
  val stringValue: String?
)
