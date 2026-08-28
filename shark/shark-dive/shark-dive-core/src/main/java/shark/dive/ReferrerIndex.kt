package shark.dive

import androidx.collection.MutableIntList
import java.util.Arrays
import java.util.Locale
import shark.HeapGraph
import shark.HeapObject
import shark.Reference
import shark.ReferenceReader
import shark.SharkLog

/**
 * Which objects point at each object of a heap dump, held in memory.
 *
 * A heap dump records a reference only in the direction it points, so answering "what holds this one"
 * means reading every object in the dump — seconds on a large one. Asking that per question is what made
 * the details panel wait; this reads the dump once and answers from memory afterwards, which is what turns
 * searching for the paths to an object into a graph walk rather than a pass over a file per step.
 *
 * Stored as one delta encoded byte slice per object, laid end to end in [referrers]: each slice is its own
 * byte length and then the referring object indexes, sorted, as the gap from the one before it, seven bits
 * of a gap to a byte. **Three to six bytes an object** on the dumps in this repo, against the twelve to
 * fifteen a linked list of two ints per reference held, so a third of what it was and 2 MB rather than 5.7
 * on the largest dump here — and a map of lists per object would be several hundred. `notes/referrer-index.md`
 * has the numbers per dump. Objects are named by [HeapObject.objectIndex] throughout for the same reason.
 *
 * Being self delimiting is what makes an index into it unnecessary: a byte offset per object would be four
 * bytes per object again, so only one per [OBJECTS_PER_BLOCK] objects is kept and a lookup steps over the
 * few slices before the one it wants. Which is also why it is faster than the linked list rather than the
 * price paid for holding less: a slice is a run of adjacent bytes where the list was a pointer chase
 * through two int arrays the size of the whole heap dump.
 */
internal class ReferrerIndex private constructor(
  private val graph: HeapGraph,
  val objectCount: Int,
  /**
   * Every object's referrers, in object index order, one slice each: the slice's own byte length as a
   * variable length int, then one variable length int per referring object, counting down from the last
   * object of the heap dump with [Reference.isLowPriority] in the low bit.
   *
   * The gap from the referrer before rather than the index itself because a byte then holds a step of up to
   * 63 rather than an object index up to 63, and a heap dump's objects are numbered in the order they were
   * written, so what points at one object tends to be written near it. Counting down rather than up because
   * [forEachReferrer] has to hand back the highest index first and a variable length int can only be read
   * forwards. The low bit rather than the top one because the top bit of a variable length int is what says
   * whether the next byte belongs to it.
   *
   * Sorted, so a gap is never negative, and deduplicated, so a referrer appears once however many of its
   * fields point at the object — with the bit set only when every one of those references had it, which
   * falls out of sorting: the lowest of a run of packed values is the one whose bit is clear.
   *
   * The length rather than the count of referrers, which is what `parttimenerd/hprof-analyzer` prefixes a
   * slice with, so that stepping over a slice is an add rather than a walk of it: an object 24 000 objects
   * point at, and there is one on nearly every dump here, would otherwise be 50 KB to read past for every
   * lookup of an object sharing its block.
   */
  private val referrers: ByteArray,
  /** Where the slice of object `block * OBJECTS_PER_BLOCK` starts in [referrers], by block. */
  private val sliceStartByBlock: IntArray
) {

  /** How much memory this holds for as long as the heap dump is open, which is what the log line says. */
  val bytesHeld: Long get() = referrers.size.toLong() + Int.SIZE_BYTES * sliceStartByBlock.size

  /** The object index of [objectId], or [NOT_AN_OBJECT] for an id the heap dump has no object for. */
  fun indexOf(objectId: Long): Int =
    graph.findObjectByIdOrNull(objectId)?.objectIndex ?: NOT_AN_OBJECT

  fun objectIdAt(objectIndex: Int): Long = graph.findObjectByIndex(objectIndex).objectId

  /**
   * Calls [block] with the object index of everything pointing at the object at [objectIndex], **highest
   * object index first**, once per referring object however many of its fields point at it, and with
   * whether every one of those references is a [Reference.isLowPriority] one.
   *
   * Highest first is not arbitrary and not free — it is what the linked list this replaced happened to hand
   * back, and a breadth first walk up the referrers takes the first of two equally distant referrers, so
   * the order decides which chain the window draws. Reversing it costs a path: the greedy search for every
   * way an object is held claims the first walk's middle, so on the heap dump
   * `HeapDiveTest.cachedPayloadHeapDump` builds, lowest first finds two chains from the same holder
   * and never reaches the cache. See `notes/referrer-index.md`.
   */
  fun forEachReferrer(
    objectIndex: Int,
    block: (Int, Boolean) -> Unit
  ) {
    var position = sliceStartByBlock[objectIndex / OBJECTS_PER_BLOCK]
    // Stepping over the objects of this block that come before it. A slice starts with its own length, so
    // one step is a read and an add however many referrers that object has — which matters because the
    // objects a whole heap dump points at are in here beside the ones nothing points at.
    var toStepOver = objectIndex % OBJECTS_PER_BLOCK
    while (toStepOver > 0) {
      val length = readVarInt(position)
      position = positionAfter(length) + valueOf(length)
      toStepOver--
    }
    val length = readVarInt(position)
    position = positionAfter(length)
    val end = position + valueOf(length)
    var previousReferrer = objectCount - 1
    while (position < end) {
      val packed = readVarInt(position)
      position = positionAfter(packed)
      val gap = valueOf(packed)
      val referrer = previousReferrer - (gap ushr 1)
      block(referrer, gap and LOW_PRIORITY_BIT != 0)
      // The next referrer is a lower index than this one, so the gap to it is stored one short.
      previousReferrer = referrer - 1
    }
  }

  /**
   * The variable length int at [position] in [referrers], with the position after it in the high 32 bits:
   * one read rather than a value and a length, since handing back two ints means allocating and this is
   * read once per referrer of every object a walk reaches.
   *
   * Read [valueOf] and [positionAfter] out of what this returns.
   */
  private fun readVarInt(position: Int): Long {
    var index = position
    var value = 0
    var shift = 0
    while (true) {
      val byte = referrers[index++].toInt()
      value = value or ((byte and VAR_INT_MASK) shl shift)
      // A byte whose top bit is set is negative, and the top bit is what says another byte follows.
      if (byte >= 0) {
        return (index.toLong() shl Int.SIZE_BITS) or (value.toLong() and INT_MASK)
      }
      shift += VAR_INT_BITS
    }
  }

  companion object {
    private const val NO_EDGE = -1

    /** No object has this index: they run from 0 to [HeapGraph.objectCount] - 1. */
    const val NOT_AN_OBJECT = -1

    /**
     * How many objects share one entry of [sliceStartByBlock]: the byte an offset per object would cost
     * against the slices a lookup has to step over to reach the one it wants, one and a half of them on
     * average at four.
     *
     * **Four rather than the sixteen `parttimenerd/hprof-analyzer` uses**, which was measured rather than
     * reasoned about: sixteen makes a walk up the referrers a third slower than the linked list this
     * replaced, eight breaks even, and four is 20% faster than it while still holding a third of what the
     * linked list did. Two and one are faster still and give back a third and a half of the bytes this
     * saves, which is the wrong way round. `notes/referrer-index.md` has the curve. Their sixteen is for a
     * heap two orders of magnitude larger, where the offsets are gigabytes and the walk is a one-off rather
     * than something a pointer moving over a treemap asks for.
     */
    private const val OBJECTS_PER_BLOCK = 4

    /** Which bit of a packed gap holds [Reference.isLowPriority]. */
    private const val LOW_PRIORITY_BIT = 1

    private const val VAR_INT_BITS = 7
    private const val VAR_INT_MASK = 0x7f
    private const val VAR_INT_CONTINUES = 0x80
    private const val INT_MASK = 0xffffffffL

    /** What is left of a packed edge of the list being compacted once the low priority bit is taken off. */
    private const val OBJECT_INDEX_MASK = Int.MAX_VALUE

    private const val EDGE_LOW_PRIORITY_BIT = Int.MIN_VALUE

    /**
     * The most objects a heap dump can have here, since an object index is packed with a bit beside it.
     * A heap dump that large is tens of gigabytes of objects, which is far past what this app can open.
     */
    private const val MAX_OBJECT_COUNT = 1 shl 30

    /** What [referrers] is grown to hold per reference before it is measured. See `notes/referrer-index.md`. */
    private const val ESTIMATED_BYTES_PER_REFERENCE = 2

    /** Enough for the referrers of nearly every object, and doubled for the few that hold more. */
    private const val INITIAL_REFERRER_CAPACITY = 16

    private fun valueOf(read: Long): Int = read.toInt()

    private fun positionAfter(read: Long): Int = (read ushr Int.SIZE_BITS).toInt()

    /**
     * Reads every object of [graph] and indexes the references [referenceReader] reports, which has to be
     * the reader the dominator tree was built with: a path through a reference the tree ignored would
     * explain a retention the tree doesn't show.
     */
    fun buildFor(
      graph: HeapGraph,
      referenceReader: ReferenceReader<HeapObject>
    ): ReferrerIndex {
      val objectCount = graph.objectCount
      require(objectCount <= MAX_OBJECT_COUNT) {
        "This heap dump has $objectCount objects, and a referrer index sorts an object index with a bit " +
          "beside it, so it can only name $MAX_OBJECT_COUNT of them. Widening that means sorting longs."
      }
      // One linked list per object first, then compacted: the references pointing at one object are spread
      // over the whole dump, so nothing can be encoded until the last object has been read, and a list to
      // prepend to is the cheapest way to hold them until then. See [compact] for what that costs.
      val lastEdgeByObjectIndex = IntArray(objectCount) { NO_EDGE }
      val referrerByEdge = MutableIntList(objectCount)
      val previousEdgeByEdge = MutableIntList(objectCount)
      graph.objects.forEach { source ->
        val referrerIndex = source.objectIndex
        referenceReader.read(source).forEach { reference ->
          val target = graph.findObjectByIdOrNull(reference.valueObjectId) ?: return@forEach
          val targetIndex = target.objectIndex
          referrerByEdge += if (reference.isLowPriority) {
            referrerIndex or EDGE_LOW_PRIORITY_BIT
          } else {
            referrerIndex
          }
          previousEdgeByEdge += lastEdgeByObjectIndex[targetIndex]
          lastEdgeByObjectIndex[targetIndex] = referrerByEdge.size - 1
        }
      }
      val referenceCount = referrerByEdge.size
      return compact(
        graph = graph,
        objectCount = objectCount,
        lastEdgeByObjectIndex = lastEdgeByObjectIndex,
        referrerByEdge = referrerByEdge,
        previousEdgeByEdge = previousEdgeByEdge
      ).also { index ->
        SharkLog.d {
          val bytesPerReference = index.bytesHeld.toDouble() / maxOf(referenceCount, 1)
          "Indexed which objects point at which: ${formatObjectCount(objectCount)}, " +
            String.format(Locale.US, "%,d references, ", referenceCount) +
            "${formatByteSize(index.bytesHeld)}, " +
            String.format(Locale.US, "%.2f bytes a reference", bytesPerReference)
        }
      }
    }

    /**
     * The linked lists of [lastEdgeByObjectIndex] read out into the encoding [referrers] describes, one
     * object at a time in object index order.
     *
     * The lists are held whole while this runs, so the build peaks on them rather than on what it writes,
     * which is where it peaked before — measured, at the same 41 MB minimum heap either way. Freeing them
     * as it goes, the way `parttimenerd/hprof-analyzer`'s `chunkvec.rs` does, can't be done from a linked
     * list: the references pointing at one object are spread over the whole dump, so this reads them in
     * scattered order rather than left to right. Making them consumable in order means a pass over the heap
     * dump to count what points at each object before any of it can be stored, which is a quarter added to
     * the time it takes to open a dump to lower a peak the ladder says is not the binding one.
     */
    private fun compact(
      graph: HeapGraph,
      objectCount: Int,
      lastEdgeByObjectIndex: IntArray,
      referrerByEdge: MutableIntList,
      previousEdgeByEdge: MutableIntList
    ): ReferrerIndex {
      val sliceStartByBlock = IntArray((objectCount + OBJECTS_PER_BLOCK - 1) / OBJECTS_PER_BLOCK)
      var encoded = ByteArray(objectCount + referrerByEdge.size * ESTIMATED_BYTES_PER_REFERENCE)
      var encodedSize = 0
      // The referrers of one object, packed as an object index with the low priority bit under it, so that
      // sorting these sorts by referrer and puts the reference to take the bit from first.
      var packedReferrers = IntArray(INITIAL_REFERRER_CAPACITY)
      for (objectIndex in 0 until objectCount) {
        if (objectIndex % OBJECTS_PER_BLOCK == 0) {
          sliceStartByBlock[objectIndex / OBJECTS_PER_BLOCK] = encodedSize
        }
        var count = 0
        var edge = lastEdgeByObjectIndex[objectIndex]
        while (edge != NO_EDGE) {
          if (count == packedReferrers.size) {
            packedReferrers = packedReferrers.copyOf(count * 2)
          }
          val packed = referrerByEdge[edge]
          packedReferrers[count++] = ((packed and OBJECT_INDEX_MASK) shl 1) or
            (if (packed < 0) LOW_PRIORITY_BIT else 0)
          edge = previousEdgeByEdge[edge]
        }
        // Nothing to sort or deduplicate below two, which is most objects of a heap dump: what holds one is
        // usually the one field of the one object that made it.
        var unique = count
        if (count > 1) {
          Arrays.sort(packedReferrers, 0, count)
          unique = 1
          for (i in 1 until count) {
            // The same referrer again: another of its fields pointing at this object, which is not another
            // way of holding it. Keeping the first of the run is what makes the bit the one every reference
            // of that referrer had, since sorting puts the reference without it first.
            if (packedReferrers[i] ushr 1 != packedReferrers[unique - 1] ushr 1) {
              packedReferrers[unique++] = packedReferrers[i]
            }
          }
        }
        var sliceLength = 0
        var previousReferrer = objectCount - 1
        for (i in unique - 1 downTo 0) {
          val referrer = packedReferrers[i] ushr 1
          sliceLength += varIntSize(((previousReferrer - referrer) shl 1) or (packedReferrers[i] and 1))
          previousReferrer = referrer - 1
        }
        val needed = encodedSize + varIntSize(sliceLength) + sliceLength
        if (needed > encoded.size) {
          encoded = encoded.copyOf(maxOf(needed, encoded.size * 2))
        }
        encodedSize = writeVarInt(encoded, encodedSize, sliceLength)
        previousReferrer = objectCount - 1
        for (i in unique - 1 downTo 0) {
          val referrer = packedReferrers[i] ushr 1
          val gap = ((previousReferrer - referrer) shl 1) or (packedReferrers[i] and 1)
          encodedSize = writeVarInt(encoded, encodedSize, gap)
          previousReferrer = referrer - 1
        }
      }
      return ReferrerIndex(
        graph = graph,
        objectCount = objectCount,
        // What growing by doubling over-allocated is worth handing back on a heap dump with millions of
        // references in it, and this is held for as long as the heap dump is open.
        referrers = if (encodedSize == encoded.size) encoded else encoded.copyOf(encodedSize),
        sliceStartByBlock = sliceStartByBlock
      )
    }

    /** How many bytes [value] takes as a variable length int, read as unsigned. */
    private fun varIntSize(value: Int): Int = when {
      value ushr 7 == 0 -> 1
      value ushr 14 == 0 -> 2
      value ushr 21 == 0 -> 3
      value ushr 28 == 0 -> 4
      else -> 5
    }

    /** Writes [value] at [position] as a variable length int, read as unsigned, and returns the end of it. */
    private fun writeVarInt(
      into: ByteArray,
      position: Int,
      value: Int
    ): Int {
      var remaining = value
      var index = position
      while (remaining ushr VAR_INT_BITS != 0) {
        into[index++] = ((remaining and VAR_INT_MASK) or VAR_INT_CONTINUES).toByte()
        remaining = remaining ushr VAR_INT_BITS
      }
      into[index++] = remaining.toByte()
      return index
    }
  }
}
