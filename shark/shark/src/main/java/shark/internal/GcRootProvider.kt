package shark.internal

import shark.GcRoot
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.IgnoredReferenceMatcher
import shark.LibraryLeakReferenceMatcher
import shark.ReferenceMatcher
import shark.ReferencePattern.NativeGlobalVariablePattern
import shark.filterFor

/**
 * Extracted from PathFinder, this should eventually be part of public API surface
 * and we should likely also revisit the gc root type filtering which happens during
 * heap parsing, as that's not really a concern for the heap parser and more for path
 * finding. There are probably memory concerns as well there though. We could:
 * - compress the storing of these roots
 * - keep only the roots locations and read / deserialize as needed
 * - Ensure a unique / consistent view of roots by doing the work of GcRootProvider
 * at parsing time and keeping that list.
 */
internal class GcRootProvider(
  private val graph: HeapGraph,
  referenceMatchers: List<ReferenceMatcher>
) {

  private val jniGlobalReferenceMatchers: Map<String, ReferenceMatcher>

  init {
    val jniGlobals = mutableMapOf<String, ReferenceMatcher>()
    referenceMatchers.filterFor(graph).forEach { referenceMatcher ->
      when (val pattern = referenceMatcher.pattern) {
        is NativeGlobalVariablePattern -> {
          jniGlobals[pattern.className] = referenceMatcher
        }
      }
    }
    this.jniGlobalReferenceMatchers = jniGlobals
  }

  class GcRootReference(
    val gcRoot: GcRoot,
    val isLowPriority: Boolean,
    val matchedLibraryLeak: LibraryLeakReferenceMatcher?,
  )

  fun provideGcRoots(): Sequence<GcRootReference> {
    return sortedGcRoots().asSequence().mapNotNull { (heapObject, gcRoot) ->
      when (gcRoot) {
        // Note: in sortedGcRoots we already filter out any java frame that has an associated
        // thread. These are the remaining ones (shouldn't be any, this is just in case).
        is JavaFrame -> {
          GcRootReference(
            gcRoot,
            isLowPriority = true,
            matchedLibraryLeak = null
          )
        }
        is JniGlobal -> {
          val referenceMatcher = when (heapObject) {
            is HeapClass -> jniGlobalReferenceMatchers[heapObject.name]
            is HeapInstance -> jniGlobalReferenceMatchers[heapObject.instanceClassName]
            is HeapObjectArray -> jniGlobalReferenceMatchers[heapObject.arrayClassName]
            is HeapPrimitiveArray -> jniGlobalReferenceMatchers[heapObject.arrayClassName]
          }
          if (referenceMatcher !is IgnoredReferenceMatcher) {
            if (referenceMatcher is LibraryLeakReferenceMatcher) {
              GcRootReference(
                gcRoot,
                isLowPriority = true,
                matchedLibraryLeak = referenceMatcher
              )
            } else {
              GcRootReference(
                gcRoot,
                isLowPriority = false,
                matchedLibraryLeak = null
              )
            }
          } else {
            null
          }
        }
        else -> {
          GcRootReference(
            gcRoot,
            isLowPriority = false,
            matchedLibraryLeak = null
          )
        }
      }
    }
  }

  /**
   * Sorting GC roots to get stable shortest path
   * Once sorted all ThreadObject Gc Roots are located before JavaLocalPattern Gc Roots.
   * This ensures ThreadObjects are visited before JavaFrames, and threadsBySerialNumber can be
   * built before JavaFrames.
   */
  private fun sortedGcRoots(): List<Pair<HeapObject, GcRoot>> {
    val rootClassName: (HeapObject) -> String = { graphObject ->
      when (graphObject) {
        is HeapClass -> {
          graphObject.name
        }
        is HeapInstance -> {
          graphObject.instanceClassName
        }
        is HeapObjectArray -> {
          graphObject.arrayClassName
        }
        is HeapPrimitiveArray -> {
          graphObject.arrayClassName
        }
      }
    }

    val threadSerialNumbers =
      ThreadObjects.getThreadObjects(graph).map { it.threadSerialNumber }.toSet()

    return graph.gcRoots
      .filter { gcRoot ->
        // GC roots sometimes reference objects that don't exist in the heap dump
        // See https://github.com/square/leakcanary/issues/1516
        graph.objectExists(gcRoot.id) &&
          // Only include java frames that do not have a corresponding ThreadObject.
          // JavaLocalReferenceReader will insert the other java frames.
          !(gcRoot is JavaFrame && gcRoot.threadSerialNumber in threadSerialNumbers)
      }
      .map { graph.findObjectById(it.id) to it }
      .sortedWith { (graphObject1, root1), (graphObject2, root2) ->
        // Sorting based on pattern name first, but we want ThreadObjects to be first because
        // they'll later enqueue java frames via JavaLocalReferenceReader in the low priority queue
        // and we want those java frames at the head of the low priority queue.
        if (root1 is ThreadObject && root2 !is ThreadObject) {
          return@sortedWith -1
        } else if (root1 !is ThreadObject && root2 is ThreadObject) {
          return@sortedWith 1
        }
        val gcRootTypeComparison = root2::class.java.name.compareTo(root1::class.java.name)
        if (gcRootTypeComparison != 0) {
          gcRootTypeComparison
        } else {
          rootClassName(graphObject1).compareTo(rootClassName(graphObject2))
        }
      }
  }
}
