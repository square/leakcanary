@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package shark

import java.util.ArrayDeque
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ValueHolder.ReferenceHolder
import shark.internal.hppc.LongScatterSet

/**
 * Computes the retained size of a single heap object by determining which objects it dominates.
 *
 * An object X *dominates* object Y if every path from a GC root to Y must pass through X.
 * The retained size of X is the sum of the shallow sizes of X and all objects X dominates —
 * in other words, the total memory that would be freed if X were garbage-collected.
 *
 * ## Algorithm
 *
 * This uses the "exclude-and-reach" (two-BFS) method, which is the standard correct approach
 * for computing a single object's dominance set without building a full dominator tree:
 *
 * 1. **BFS without X**: traverse the heap from all GC roots, treating X as removed. Mark every
 *    object reached as "reachable-without-X".
 *
 * 2. **BFS from X**: traverse the heap starting from X. Every object that is reachable from X
 *    but NOT in the "reachable-without-X" set is exclusively dominated by X.
 *
 * 3. **Sum sizes**: the retained size equals the sum of the shallow sizes of X and its dominated
 *    set.
 *
 * This is provably correct for any directed reference graph, without any assumptions about
 * traversal order.
 *
 * ## Object size
 *
 * Shallow sizes are computed by [AndroidObjectSizeCalculator], which combines:
 * - The declared instance-field byte size from the HPROF class dump (what the VM reports as the
 *   object's on-heap footprint).
 * - Any native-side allocations tracked via [AndroidNativeSizeMapper] (e.g. Bitmap pixel
 *   buffers stored in native memory but associated with a heap object).
 *
 * This matches what LeakCanary's [HeapAnalyzer] and Android Studio's Memory Profiler report for
 * retained sizes on Android. Pure JVM tools such as Eclipse MAT only count on-heap field sizes
 * without the native supplement; we include it because on Android a significant fraction of an
 * object's true memory cost often lives in native memory.
 */
class SingleObjectRetainedSizeCalculator(private val graph: HeapGraph) {

  /**
   * @param objectId the heap ID of the object whose retained size was computed.
   * @param retainedObjectCount the total number of objects in the retained set, including
   *   [objectId] itself.
   * @param retainedSize total retained memory: [objectId] plus all dominated objects.
   */
  data class Result(
    val objectId: Long,
    val retainedObjectCount: Int,
    val retainedSize: ByteSize
  )

  /**
   * Computes the retained size of the object identified by [objectId].
   *
   * This traverses the entire heap twice and is therefore a slow operation on large heap dumps.
   */
  fun computeRetainedSize(objectId: Long): Result {
    // ------------------------------------------------------------------
    // Step 1: BFS from all GC roots, bypassing objectId.
    //         Every object reached here is reachable WITHOUT objectId.
    // ------------------------------------------------------------------
    val reachableWithout = LongScatterSet(graph.objectCount / 4)
    val queue = ArrayDeque<Long>()

    for (gcRoot in graph.gcRoots) {
      val rootId = gcRoot.id
      if (rootId == ValueHolder.NULL_REFERENCE || !graph.objectExists(rootId)) continue
      if (rootId == objectId) continue
      if (reachableWithout.add(rootId)) queue.add(rootId)
    }

    while (queue.isNotEmpty()) {
      val current = queue.poll()
      val obj = graph.findObjectByIdOrNull(current) ?: continue
      for (refId in outgoingObjectIds(obj)) {
        if (refId == objectId) continue
        if (reachableWithout.add(refId)) queue.add(refId)
      }
    }

    // ------------------------------------------------------------------
    // Step 2: BFS from objectId.
    //         Objects not in reachableWithout are exclusively dominated by objectId.
    // ------------------------------------------------------------------
    val retained = LongScatterSet()
    if (graph.objectExists(objectId)) {
      retained.add(objectId)
      queue.add(objectId)
      while (queue.isNotEmpty()) {
        val current = queue.poll()
        val obj = graph.findObjectByIdOrNull(current) ?: continue
        for (refId in outgoingObjectIds(obj)) {
          if (!reachableWithout.contains(refId) && retained.add(refId)) {
            queue.add(refId)
          }
        }
      }
    }

    // ------------------------------------------------------------------
    // Step 3: Sum shallow (+ native) sizes over the retained set.
    // ------------------------------------------------------------------
    val sizeCalculator = AndroidObjectSizeCalculator(graph)
    var totalBytes = 0L
    retained.elementSequence().forEach { id ->
      totalBytes += sizeCalculator.computeSize(id)
    }

    return Result(
      objectId = objectId,
      retainedObjectCount = retained.size(),
      retainedSize = ByteSize(totalBytes)
    )
  }

  /**
   * Returns the object IDs of all objects directly referenced by [obj].
   * Primitive arrays have no object references; all other types are fully traversed.
   */
  private fun outgoingObjectIds(obj: HeapObject): Sequence<Long> = when (obj) {
    is HeapInstance -> obj.readFields().mapNotNull { field ->
      val holder = field.value.holder
      if (holder is ReferenceHolder && !holder.isNull) holder.value else null
    }
    is HeapObjectArray -> obj.readRecord().elementIds.asSequence()
      .filter { it != ValueHolder.NULL_REFERENCE }
    is HeapClass -> obj.readStaticFields().mapNotNull { field ->
      val holder = field.value.holder
      if (holder is ReferenceHolder && !holder.isNull) holder.value else null
    }
    is HeapPrimitiveArray -> emptySequence()
  }
}
