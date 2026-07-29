package shark.explorer

import androidx.collection.LongIntMap
import androidx.collection.MutableLongIntMap
import shark.AndroidNativeSizeMapper
import shark.GcRootProvider
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ObjectSizeCalculator
import shark.explorer.ReachabilityStrength.STRONG

/**
 * How the bytes of a heap dump split up by [ReachabilityStrength].
 *
 * The three numbers the UI shows at the top come from here, and they add up:
 * [reachableByteCount] + [unreachableByteCount] == [totalByteCount].
 */
data class HeapSizes(
  /** Bytes held by the objects reachable at each strength, with an entry for every strength. */
  val byteCountByStrength: Map<ReachabilityStrength, Long>,
  /**
   * Bytes held by objects no GC root reaches at all: garbage that hadn't been collected when the heap
   * dump was written.
   */
  val unreachableByteCount: Long,
  /** Every object in the heap dump, reachable or not, plus the native memory they hold. */
  val totalByteCount: Long
) {

  /** Bytes held by every object a GC root reaches, however weakly. */
  val reachableByteCount: Long
    get() = byteCountByStrength.values.sum()
}

/**
 * Which objects of a heap dump are reachable from its GC roots, and how strongly.
 *
 * Computed with a breadth first walk per strength, strongest first: an object is assigned the
 * strength of the first walk that reaches it, and each walk queues the referents it finds into the
 * walk for `max(its own strength, the referent's)`. Because a walk only ever queues into itself or
 * into a weaker one, and every stronger walk has finished by the time a walk starts, the first walk to
 * reach an object is the strongest path to it. That's the strongest-of-the-weakest-links definition in
 * [ReachabilityStrength], computed in one pass per strength rather than by iterating to a fixed point.
 */
internal class HeapReachability private constructor(
  /**
   * Strength ordinal by object id, holding only the objects that aren't [STRONG]. A heap dump is
   * almost entirely strongly reachable, so keeping the whole mapping would cost tens of MB to say
   * "strong" over and over.
   */
  private val weakerStrengthOrdinalByObjectId: LongIntMap,
  val sizes: HeapSizes
) {

  /**
   * How strongly [objectId] is reachable, [STRONG] for an object this doesn't know about. Only ask
   * about objects known to be reachable, e.g. the nodes of a dominator tree.
   */
  fun strengthOf(objectId: Long): ReachabilityStrength =
    STRENGTHS[weakerStrengthOrdinalByObjectId.getOrDefault(objectId, STRONG.ordinal)]

  companion object {
    private val STRENGTHS = ReachabilityStrength.values()

    private const val NOT_REACHED = 0.toByte()

    fun computeFor(
      graph: HeapGraph,
      strengthReader: ReferenceStrengthReader,
      gcRootProvider: GcRootProvider,
      objectSizeCalculator: ObjectSizeCalculator
    ): HeapReachability {
      // A reached object holds its strength ordinal + 1, so that 0 means "not reached yet".
      val reachedStrengthByObjectIndex = ByteArray(graph.objectCount)
      // One queue of object ids per strength, drained strongest first.
      val queueByStrength = Array(STRENGTHS.size) { ArrayDeque<Long>() }
      val byteCountByStrength = LongArray(STRENGTHS.size)
      val weakerStrengthOrdinalByObjectId = MutableLongIntMap()

      gcRootProvider.provideGcRoots(graph).forEach { rootReference ->
        queueByStrength[STRONG.ordinal] += rootReference.gcRoot.id
      }

      STRENGTHS.forEach { strength ->
        val queue = queueByStrength[strength.ordinal]
        while (queue.isNotEmpty()) {
          val heapObject = graph.findObjectByIdOrNull(queue.removeFirst()) ?: continue
          if (reachedStrengthByObjectIndex[heapObject.objectIndex] != NOT_REACHED) {
            continue
          }
          reachedStrengthByObjectIndex[heapObject.objectIndex] = (strength.ordinal + 1).toByte()
          byteCountByStrength[strength.ordinal] +=
            objectSizeCalculator.computeSize(heapObject.objectId)
          if (strength != STRONG) {
            weakerStrengthOrdinalByObjectId[heapObject.objectId] = strength.ordinal
          }
          // A reference that retains its target doesn't weaken the path it's on.
          strengthReader.retainingReferencesOf(heapObject).forEach { reference ->
            queue += reference.valueObjectId
          }
          strengthReader.weakeningReferencesOf(heapObject).forEach { weakening ->
            queueByStrength[maxOf(strength, weakening.strength).ordinal] += weakening.valueObjectId
          }
        }
      }

      val totalByteCount = totalByteCount(graph)
      return HeapReachability(
        weakerStrengthOrdinalByObjectId = weakerStrengthOrdinalByObjectId,
        sizes = HeapSizes(
          byteCountByStrength = STRENGTHS.associateWith { byteCountByStrength[it.ordinal] },
          unreachableByteCount = (totalByteCount - byteCountByStrength.sum()).coerceAtLeast(0L),
          totalByteCount = totalByteCount
        )
      )
    }

    /**
     * Every byte of every object in [graph], plus the native memory they hold on to.
     *
     * Not [ObjectSizeCalculator]: that one folds a string's char array into the string, because the
     * reference readers skip the `value` field and the array is no node of its own. Which is exactly
     * why summing [ObjectSizeCalculator] over the reachable objects and subtracting from this gives
     * the unreachable bytes: the folded objects are counted once, inside the object they were folded
     * into, on both sides of the subtraction.
     *
     * The one case it gets wrong is a folded array that something else also points at, which is
     * counted twice on the reachable side and makes the unreachable count come out low.
     */
    private fun totalByteCount(graph: HeapGraph): Long {
      val objectByteCount = graph.objects.sumOf { heapObject ->
        when (heapObject) {
          is HeapInstance -> heapObject.byteSize
          is HeapObjectArray -> heapObject.byteSize
          is HeapPrimitiveArray -> heapObject.byteSize
          is HeapClass -> heapObject.recordSize
        }.toLong()
      }
      val nativeByteCount = AndroidNativeSizeMapper(graph).mapNativeSizes()
        .values
        .sumOf { it.toLong() }
      return objectByteCount + nativeByteCount
    }
  }
}
