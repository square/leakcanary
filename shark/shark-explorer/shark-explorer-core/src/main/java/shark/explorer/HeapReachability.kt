package shark.explorer

import java.util.BitSet
import shark.AndroidNativeSizeMapper
import shark.GcRootProvider
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.ObjectSizeCalculator
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.UNREACHABLE

/**
 * How the objects of a heap dump split up by [ReachabilityStrength], in bytes and in objects.
 *
 * Every object has exactly one strength, [UNREACHABLE] included, so both breakdowns are of the whole
 * heap dump: the object counts add up to [totalObjectCount] exactly, and the byte counts to
 * [totalByteCount] give or take the objects Shark counts inside another one — see
 * [ReferenceStrengthReader.foldedObjectIdsOf].
 */
data class HeapSizes(
  /** Bytes held by the objects at each strength, with an entry for every strength. */
  val byteCountByStrength: Map<ReachabilityStrength, Long>,
  /** How many objects there are at each strength, with an entry for every strength. */
  val objectCountByStrength: Map<ReachabilityStrength, Int>,
  /** Every byte of every object in the heap dump, plus the native memory they hold. */
  val totalByteCount: Long,
  /** Every object in the heap dump, reachable or not. */
  val totalObjectCount: Int
) {

  /** Bytes held by every object a GC root reaches, however weakly. */
  val reachableByteCount: Long
    get() = byteCountByStrength.entries.sumOf { (strength, byteCount) ->
      if (strength == UNREACHABLE) 0L else byteCount
    }

  /**
   * Bytes held by objects no GC root reaches at all: garbage that hadn't been collected when the heap
   * dump was written.
   */
  val unreachableByteCount: Long
    get() = byteCountByStrength.getValue(UNREACHABLE)

  /** How many objects a GC root reaches, however weakly. */
  val reachableObjectCount: Int
    get() = totalObjectCount - unreachableObjectCount

  /** How many objects none of them reaches. */
  val unreachableObjectCount: Int
    get() = objectCountByStrength.getValue(UNREACHABLE)
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
 *
 * Then what the walks didn't reach is the uncollected garbage, and [unreachableRootObjectIds] is where a
 * walk of it starts.
 */
internal class HeapReachability private constructor(
  private val graph: HeapGraph,
  /**
   * Strength ordinal + 1 by object index, 0 for an object whose bytes are folded into another one. A
   * byte per object rather than a map of the objects that aren't [STRONG]: a heap dump is almost
   * entirely strongly reachable, and this way the array is smaller than the map would be and says
   * something about every object.
   */
  private val strengthByObjectIndex: ByteArray,
  val sizes: HeapSizes,
  /**
   * The unreachable objects nothing unreachable points at, so a walk from them reaches all of the
   * garbage — plus one object per cycle that has no way in, which would otherwise be walked from
   * nowhere. Passing these to [shark.HeapDominatorTree] as GC roots is what puts the garbage in the
   * tree, each piece of it under whatever held it.
   */
  val unreachableRootObjectIds: List<Long>
) {

  /**
   * How strongly [objectId] is reachable. [UNREACHABLE] for an object the GC roots don't reach, and for
   * one whose bytes are folded into another object, which is as close to the truth as this gets for
   * something that isn't a node of the graph — see [ReferenceStrengthReader.foldedObjectIdsOf].
   */
  fun strengthOf(objectId: Long): ReachabilityStrength {
    val heapObject = graph.findObjectByIdOrNull(objectId) ?: return UNREACHABLE
    return strengthOf(heapObject)
  }

  fun strengthOf(heapObject: HeapObject): ReachabilityStrength =
    strengthByObjectIndex.strengthAt(heapObject.objectIndex)

  companion object {
    private val STRENGTHS = ReachabilityStrength.values()

    /** The strengths a walk from the GC roots can hand out, which is all of them but [UNREACHABLE]. */
    private val REACHED_STRENGTHS = STRENGTHS.filter { it != UNREACHABLE }

    private const val NOT_REACHED = 0

    /**
     * The strength one object index was given. An object the walks didn't reach is [UNREACHABLE], and so
     * is one whose bytes are folded into a piece of garbage: nothing distinguishes the two here, and
     * nothing needs to.
     */
    private fun ByteArray.strengthAt(objectIndex: Int): ReachabilityStrength =
      this[objectIndex].toInt().let { ordinalPlusOne ->
        if (ordinalPlusOne == NOT_REACHED) UNREACHABLE else STRENGTHS[ordinalPlusOne - 1]
      }

    fun computeFor(
      graph: HeapGraph,
      strengthReader: ReferenceStrengthReader,
      gcRootProvider: GcRootProvider,
      objectSizeCalculator: ObjectSizeCalculator
    ): HeapReachability {
      val walk = Walk(graph, strengthReader, objectSizeCalculator)
      walk.walkFromGcRoots(gcRootProvider)
      val unreachableObjectIds = walk.markUnreachable()
      return HeapReachability(
        graph = graph,
        strengthByObjectIndex = walk.strengthByObjectIndex,
        sizes = walk.sizes(totalByteCount(graph)),
        unreachableRootObjectIds = walk.rootsOf(unreachableObjectIds)
      )
    }

    /**
     * Every byte of every object in [graph], plus the native memory they hold on to.
     *
     * Not [ObjectSizeCalculator]: that one folds a string's char array into the string, because the
     * reference readers skip the `value` field and the array is no node of its own. Summing the byte
     * counts per strength therefore comes out a little under this, by the size of the folded objects
     * something else also points at, which are counted once inside their holder and once as a node.
     */
    private fun totalByteCount(graph: HeapGraph): Long {
      val objectByteCount = graph.objects.sumOf { heapObject ->
        when (heapObject) {
          is HeapInstance -> heapObject.byteSize
          is HeapObjectArray -> heapObject.byteSize
          is HeapPrimitiveArray -> heapObject.byteSize
          is HeapClass -> heapObject.recordSize
        }
      }
      val nativeByteCount = AndroidNativeSizeMapper(graph).mapNativeSizes()
        .values
        .sumOf { it.toLong() }
      return objectByteCount + nativeByteCount
    }
  }

  /**
   * One reachability computation: the state the walks share, and the passes over the heap dump that
   * fill it in, in the order [computeFor] runs them.
   */
  private class Walk(
    private val graph: HeapGraph,
    private val strengthReader: ReferenceStrengthReader,
    private val objectSizeCalculator: ObjectSizeCalculator
  ) {

    val strengthByObjectIndex = ByteArray(graph.objectCount)

    /**
     * The objects whose bytes are counted inside another one. Kept apart from the strengths because
     * being folded says nothing about how firmly an object is held, and because a folded object of a
     * piece of garbage looks exactly like garbage otherwise.
     */
    private val foldedObjectIndexes = BitSet(graph.objectCount)

    private val byteCountByStrength = LongArray(STRENGTHS.size)

    /** Assigns every object the strength of the strongest path from a GC root to it. */
    fun walkFromGcRoots(gcRootProvider: GcRootProvider) {
      // One queue of object ids per strength, drained strongest first.
      val queueByStrength = Array(STRENGTHS.size) { ArrayDeque<Long>() }
      gcRootProvider.provideGcRoots(graph).forEach { rootReference ->
        queueByStrength[STRONG.ordinal] += rootReference.gcRoot.id
      }
      REACHED_STRENGTHS.forEach { strength ->
        val queue = queueByStrength[strength.ordinal]
        while (queue.isNotEmpty()) {
          val heapObject = graph.findObjectByIdOrNull(queue.removeFirst()) ?: continue
          if (!reach(heapObject, strength)) {
            continue
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
    }

    /**
     * Gives every object the walks didn't reach the [UNREACHABLE] strength, and returns them in the
     * order the heap dump records them.
     *
     * Skips the ones whose bytes are folded into another object: a garbage string's characters are
     * already counted inside the string, so listing the array as garbage of its own would count those
     * bytes twice and draw a rectangle for something the tree has no node for.
     */
    fun markUnreachable(): List<Long> {
      val candidateIds = mutableListOf<Long>()
      graph.objects.forEach { heapObject ->
        if (strengthByObjectIndex[heapObject.objectIndex].toInt() == NOT_REACHED &&
          !foldedObjectIndexes.get(heapObject.objectIndex)
        ) {
          candidateIds += heapObject.objectId
          // A candidate can turn out to be folded into another candidate seen later in this pass, hence
          // the second look below rather than a decision here.
          fold(heapObject)
        }
      }
      return candidateIds.filter { objectId ->
        val heapObject = graph.findObjectById(objectId)
        !foldedObjectIndexes.get(heapObject.objectIndex) && reach(heapObject, UNREACHABLE)
      }
    }

    /**
     * Which of [unreachableObjectIds] a walk of the garbage has to start from: the ones no other piece
     * of garbage points at, plus a way into whatever that leaves out.
     *
     * What it leaves out is cycles: a doubly linked list nothing points at any more has every one of its
     * nodes pointed at by another, so none of them is an entry point, and a walk from the others never
     * arrives. So this walks from the entry points it found and picks the first object of the heap dump
     * that walk didn't reach, over and over. Which object of a cycle that is, is arbitrary — nothing in
     * the heap dump makes one of them the owner.
     */
    fun rootsOf(unreachableObjectIds: List<Long>): List<Long> {
      val pointedAt = BitSet(graph.objectCount)
      unreachableObjectIds.forEach { objectId ->
        forEachUnreachableReferent(objectId) { referent -> pointedAt.set(referent.objectIndex) }
      }
      val roots = unreachableObjectIds.filterTo(mutableListOf()) { objectId ->
        !pointedAt.get(graph.findObjectById(objectId).objectIndex)
      }
      val walked = BitSet(graph.objectCount)
      walkUnreachable(roots, walked)
      unreachableObjectIds.forEach { objectId ->
        if (!walked.get(graph.findObjectById(objectId).objectIndex)) {
          roots += objectId
          walkUnreachable(listOf(objectId), walked)
        }
      }
      return roots
    }

    fun sizes(totalByteCount: Long): HeapSizes {
      val objectCountByStrength = IntArray(STRENGTHS.size)
      // A folded object counts as one of the heap dump's objects like any other, held as firmly as
      // whatever holds it, which is the strength the array has for it.
      strengthByObjectIndex.indices.forEach { objectIndex ->
        objectCountByStrength[strengthByObjectIndex.strengthAt(objectIndex).ordinal]++
      }
      return HeapSizes(
        byteCountByStrength = STRENGTHS.associateWith { byteCountByStrength[it.ordinal] },
        objectCountByStrength = STRENGTHS.associateWith { objectCountByStrength[it.ordinal] },
        totalByteCount = totalByteCount,
        totalObjectCount = graph.objectCount
      )
    }

    /**
     * Records [heapObject] as reachable at [strength] and counts its bytes, or answers false if it was
     * already reached — the first walk to reach an object is the strongest path to it.
     */
    private fun reach(
      heapObject: HeapObject,
      strength: ReachabilityStrength
    ): Boolean {
      if (strengthByObjectIndex[heapObject.objectIndex].toInt() != NOT_REACHED) {
        return false
      }
      strengthByObjectIndex[heapObject.objectIndex] = (strength.ordinal + 1).toByte()
      byteCountByStrength[strength.ordinal] += objectSizeCalculator.computeSize(heapObject.objectId)
      fold(heapObject)
      return true
    }

    /**
     * Marks the objects [heapObject]'s size already covers, at its own strength: they're held exactly as
     * firmly as it is, and their bytes are counted once, inside it.
     */
    private fun fold(heapObject: HeapObject) {
      val strength = strengthByObjectIndex[heapObject.objectIndex]
      strengthReader.foldedObjectIdsOf(heapObject).forEach { foldedId ->
        val folded = graph.findObjectByIdOrNull(foldedId) ?: return@forEach
        foldedObjectIndexes.set(folded.objectIndex)
        if (strengthByObjectIndex[folded.objectIndex].toInt() == NOT_REACHED) {
          strengthByObjectIndex[folded.objectIndex] = strength
        }
      }
    }

    /** Depth first from [fromObjectIds] through the garbage, marking what it reaches in [walked]. */
    private fun walkUnreachable(
      fromObjectIds: List<Long>,
      walked: BitSet
    ) {
      val stack = ArrayDeque(fromObjectIds)
      while (stack.isNotEmpty()) {
        val objectId = stack.removeLast()
        val objectIndex = graph.findObjectById(objectId).objectIndex
        if (walked.get(objectIndex)) {
          continue
        }
        walked.set(objectIndex)
        forEachUnreachableReferent(objectId) { referent -> stack += referent.objectId }
      }
    }

    /**
     * Runs [block] for every unreachable object the unreachable [objectId] points at, which is the same
     * set of edges [WeakeningAwareReferenceReader] gives the dominator tree: a reference that doesn't
     * retain is followed when nothing stronger holds its target, and nothing at all holds these.
     */
    private inline fun forEachUnreachableReferent(
      objectId: Long,
      block: (HeapObject) -> Unit
    ) {
      val source = graph.findObjectById(objectId)
      val referentIds = strengthReader.retainingReferencesOf(source).map { it.valueObjectId } +
        strengthReader.weakeningReferencesOf(source).map { it.valueObjectId }
      referentIds.forEach { referentId ->
        val referent = graph.findObjectByIdOrNull(referentId)
        if (referent != null &&
          strengthByObjectIndex[referent.objectIndex].toInt() == UNREACHABLE.ordinal + 1
        ) {
          block(referent)
        }
      }
    }
  }
}
