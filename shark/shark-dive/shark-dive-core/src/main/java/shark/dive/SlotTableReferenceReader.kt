package shark.dive

import androidx.collection.LongObjectMap
import androidx.collection.LongSet
import androidx.collection.MutableIntList
import androidx.collection.MutableLongList
import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.Reference
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.SharkLog
import shark.ValueHolder

/**
 * What a Compose composition holds, read as references from the node each value belongs to — the
 * `LayoutNode` whose composable remembered it — rather than from the one flat array the composition
 * really keeps it all in.
 *
 * A `SlotTable` is that array plus an `int[]` describing it: every `remember` of every composable in a
 * window, every composition local map, every lambda, every `LayoutNode`, side by side in one `Object[]`
 * that grows to the size of the UI. Read as it is stored, a composition is one flat list of thousands of
 * unrelated objects, so an image a screen remembers is held by the same array as an image five screens
 * away, and the answer to "what is holding this bitmap" is a composition rather than a piece of UI.
 *
 * The `int[]` is what makes the shape recoverable. It is a fixed number of ints per group, laid out in
 * the order the composables ran, where a group knows how many groups it contains and where its own slots
 * start, and where a group that emitted a node keeps that node in its first slot. So the enclosing node
 * of any slot is a walk of that array away, and the slots between two groups are one group's.
 *
 * **Unlike [ViewChildReferenceReader] this replaces the array's references rather than adding to them**,
 * which is the one thing to understand before changing it. Adding worked for a `ViewGroup` because the
 * `View[]` sits under the parent, so both ways to a child start there and the parent dominates it. A
 * composition's array sits under the composition, nowhere near the UI the values belong to, so an added
 * reference would only give a bitmap a second way of being reached and push it further up — to whatever
 * dominates both the composition and the screen, which is the top of the tree. The array is still a node
 * holding its own bytes, still reached through the table's own field, and every one of its elements is
 * still reached exactly once, which is what [ReferenceStrengthReader] needs of a reader here. Only which
 * object is named as holding an element changes.
 *
 * What that buys, and what it costs, are the same thing: a remembered value is attributed to the node
 * that remembers it, so a composable's images are its own, unless something outside the UI holds the
 * value too — then the two paths meet above both and the value belongs to neither, which is the honest
 * answer for something a screen and a repository share.
 */
internal class SlotTableReferenceReader(private val graph: HeapGraph) {

  /**
   * Built on first use rather than in a constructor, and as a whole object assigned at once, so that a
   * read given up on half way is retried rather than remembered — the rule for every index here, see the
   * shark-dive AGENTS guide. Empty for a heap dump with no composition in it.
   */
  private val slots: TableSlots by lazy { readSlotTables() }

  /**
   * The slots of every group [source] holds: what its composables remembered, the keys they were
   * remembered under, and the nodes they emitted. Empty for anything else, which is nearly every object
   * of a heap dump.
   */
  fun groupReferencesOf(source: HeapObject): Sequence<Reference> {
    if (source !is HeapInstance) {
      return emptySequence()
    }
    val slotsHeld = slots.slotsBySourceId[source.objectId] ?: return emptySequence()
    val locationClassObjectId = source.instanceClassId
    // Iterated by index rather than as a sequence of pairs, so that reading it twice reads the same
    // slots and boxes nothing: this runs for every reference of every node of the tree.
    return sequence {
      for (pair in 0 until slotsHeld.size / 2) {
        val slotIndex = slotsHeld[pair * 2]
        val valueObjectId = slotsHeld[pair * 2 + 1]
        yield(
          Reference(
            valueObjectId = valueObjectId,
            isLowPriority = false,
            lazyDetailsResolver = {
              LazyDetails(
                // The index in the table the value really is at, so that a path says where to look for
                // it. There is no field here, and the class of the array the slot is in would name the
                // composition rather than the node holding the slot.
                name = "slot $slotIndex",
                locationClassObjectId = locationClassObjectId,
                locationType = ARRAY_ENTRY,
                isVirtual = true,
                matchedLibraryLeak = null
              )
            }
          )
        )
      }
    }
  }

  /**
   * Whether [source] is the array a composition keeps its slots in, whose elements [groupReferencesOf]
   * hands out from the groups holding them instead — so it holds nothing of its own.
   */
  fun slotsReadFromTheirGroups(source: HeapObject): Boolean =
    source is HeapObjectArray && slots.tableSlotArrayIds.contains(source.objectId)

  /**
   * Reads every slot table of the heap dump, which costs a class lookup per name below and a handful of
   * record reads per composition — a table, its two arrays, and nothing per object of the heap dump.
   */
  private fun readSlotTables(): TableSlots {
    val slotsBySourceId = MutableLongObjectMap<MutableLongList>()
    val tableSlotArrayIds = MutableLongSet()
    SLOT_TABLE_CLASS_NAMES.forEach { className ->
      graph.findClassByName(className)?.instances?.forEach { table ->
        readTable(table, slotsBySourceId, tableSlotArrayIds)
      }
    }
    return TableSlots(slotsBySourceId, tableSlotArrayIds)
  }

  /**
   * Reads one composition's slots into [slotsBySourceId], and records its array in [tableSlotArrayIds]
   * when it read all of them.
   *
   * All or nothing per table: an array whose elements are only partly handed out from their groups would
   * leave the rest reachable through nothing, so a table this can't make sense of keeps its own
   * references and reads the way it did before this class existed.
   */
  private fun readTable(
    table: HeapInstance,
    slotsBySourceId: MutableLongObjectMap<MutableLongList>,
    tableSlotArrayIds: MutableLongSet
  ) {
    val arrays = arraysOf(table) ?: return
    val slotArrayIds = arrays.slots.readRecord().elementIds
    val describesItsSlots = describesItsSlots(
      table = table,
      groups = arrays.groups,
      groupCount = arrays.groupCount,
      slotArraySize = slotArrayIds.size,
      slotCount = arrays.slotCount
    )
    if (!describesItsSlots) {
      return
    }
    readGroups(
      tableObjectId = table.objectId,
      groups = arrays.groups,
      groupCount = arrays.groupCount,
      slotArrayIds = slotArrayIds,
      slotCount = arrays.slotCount,
      slotsBySourceId = slotsBySourceId
    )
    tableSlotArrayIds += arrays.slots.objectId
  }

  /**
   * The two arrays [table] keeps its state in and how much of each is in use, or null when it holds them
   * in a shape this can't read — which is a Compose version that moved them, and so is worth a line of
   * the log rather than a failure: a table skipped here reads the way it did before this class existed.
   */
  private fun arraysOf(table: HeapInstance): TableArrays? {
    var groups: IntArray? = null
    var slots: HeapObjectArray? = null
    var groupCount = -1
    var slotCount = -1
    // Fields matched by name against the whole instance rather than by their declaring class, since
    // which class of the hierarchy declares them is exactly what moves between Compose versions.
    table.readFields().forEach { field ->
      when (field.name) {
        GROUPS_FIELD_NAME -> groups =
          (field.valueAsPrimitiveArray?.readRecord() as? IntArrayDump)?.array
        SLOTS_FIELD_NAME -> slots = field.valueAsObjectArray
        GROUPS_SIZE_FIELD_NAME -> groupCount = field.value.asInt ?: -1
        SLOTS_SIZE_FIELD_NAME -> slotCount = field.value.asInt ?: -1
      }
    }
    val groupInts = groups
    val slotArray = slots
    val unreadable = when {
      groupInts == null -> "no $GROUPS_FIELD_NAME of ints describing them"
      slotArray == null -> "no $SLOTS_FIELD_NAME array to read them out of"
      groupCount < 0 || slotCount < 0 -> "$groupCount groups over $slotCount slots"
      else -> return TableArrays(groupInts, slotArray, groupCount, slotCount)
    }
    SharkLog.d {
      "${table.instanceClassSimpleName} keeps its slots in a shape this can't read — $unreadable — so " +
        "what it holds reads as the composition's rather than as the UI's"
    }
    return null
  }

  /**
   * Whether the `int[]` of [table] describes the slots of [slotArraySize] as this reads it: a whole
   * number of groups, each containing groups that are there, and each starting its data where the
   * previous group's ended.
   *
   * A heap dump is a file and a file can say anything, but the reason to check is nearer than that. A
   * composition being written to while the heap was dumped has its two arrays half moved — the gap they
   * are written through is wherever the writer left it, and the anchors on the far side of it are stored
   * negative — so the ordering below is what tells a table that can be read from one caught mid-change.
   */
  @Suppress("ReturnCount")
  private fun describesItsSlots(
    table: HeapInstance,
    groups: IntArray,
    groupCount: Int,
    slotArraySize: Int,
    slotCount: Int
  ): Boolean {
    fun refuse(reason: String): Boolean {
      SharkLog.d {
        "${table.instanceClassSimpleName} describes $groupCount groups over $slotCount slots and " +
          "$reason, so what it holds reads as the composition's rather than as the UI's"
      }
      return false
    }
    if (groupCount * GROUP_INT_COUNT > groups.size) {
      return refuse("says more groups than its ${groups.size} ints describe")
    }
    if (slotCount > slotArraySize) {
      return refuse("says more slots than the $slotArraySize it has")
    }
    var previousDataStart = 0
    for (address in 0 until groupCount) {
      val dataStart = groups[address * GROUP_INT_COUNT + DATA_ANCHOR_INT]
      if (dataStart < previousDataStart || dataStart > slotCount) {
        return refuse("has group $address starting its slots at $dataStart")
      }
      previousDataStart = dataStart
      val size = groups[address * GROUP_INT_COUNT + GROUP_SIZE_INT]
      if (size < 1 || address + size > groupCount) {
        return refuse("has group $address containing $size groups")
      }
    }
    return true
  }

  /**
   * Walks the groups of one composition in the order they ran, recording each group's slots against the
   * node it is inside — or against the table itself, for the groups above the first node of it.
   *
   * The groups are depth first, and a group says how many groups it contains, so the group in scope at
   * any point is the innermost one that hasn't run out: a stack of where each ends is the whole of
   * knowing which node a slot belongs to.
   */
  private fun readGroups(
    tableObjectId: Long,
    groups: IntArray,
    groupCount: Int,
    slotArrayIds: LongArray,
    slotCount: Int,
    slotsBySourceId: MutableLongObjectMap<MutableLongList>
  ) {
    val enclosingEnds = MutableIntList()
    val enclosingSourceIds = MutableLongList()
    for (address in 0 until groupCount) {
      while (enclosingEnds.isNotEmpty() && address >= enclosingEnds.last()) {
        enclosingEnds.removeAt(enclosingEnds.lastIndex)
        enclosingSourceIds.removeAt(enclosingSourceIds.lastIndex)
      }
      val enclosingSourceId =
        if (enclosingSourceIds.isEmpty()) tableObjectId else enclosingSourceIds.last()
      val groupInfo = groups[address * GROUP_INT_COUNT + GROUP_INFO_INT]
      val dataStart = groups[address * GROUP_INT_COUNT + DATA_ANCHOR_INT]
      // Where the next group's data starts is where this one's ends, the data being laid out in the
      // same order as the groups. The last group's ends at the last slot in use, the array past that
      // being the gap the next write will be made through.
      val dataEnd = if (address + 1 < groupCount) {
        groups[(address + 1) * GROUP_INT_COUNT + DATA_ANCHOR_INT]
      } else {
        slotCount
      }
      var slotIndex = dataStart
      var sourceId = enclosingSourceId
      if (groupInfo and NODE_GROUP_MASK != 0 && slotIndex < dataEnd) {
        // A node group keeps its node in its first slot, and everything after that belongs to the node:
        // the node itself is the enclosing group's, which is the reference the node tree already has,
        // so the two agree and the parent gets to hold its child.
        val node = graph.findObjectByIdOrNull(slotArrayIds[slotIndex])
        if (node is HeapInstance) {
          slotsBySourceId.slotsOf(enclosingSourceId).addSlot(slotIndex, node.objectId)
          sourceId = node.objectId
        }
        slotIndex++
      }
      while (slotIndex < dataEnd) {
        val valueObjectId = slotArrayIds[slotIndex]
        // Nothing holds itself: a group that remembers the node it emitted would otherwise be the only
        // thing reaching it, which is no way of reaching it at all.
        if (valueObjectId != ValueHolder.NULL_REFERENCE && valueObjectId != sourceId &&
          graph.objectExists(valueObjectId)
        ) {
          slotsBySourceId.slotsOf(sourceId).addSlot(slotIndex, valueObjectId)
        }
        slotIndex++
      }
      enclosingEnds += address + groups[address * GROUP_INT_COUNT + GROUP_SIZE_INT]
      enclosingSourceIds += sourceId
    }
    // The slots the table isn't using: the gap a write is made through, which the writer nulls as it
    // gives a slot up, and which is the composition's own either way.
    for (index in slotCount until slotArrayIds.size) {
      val valueObjectId = slotArrayIds[index]
      if (valueObjectId != ValueHolder.NULL_REFERENCE && graph.objectExists(valueObjectId)) {
        slotsBySourceId.slotsOf(tableObjectId).addSlot(index, valueObjectId)
      }
    }
  }

  /**
   * The slots every object of a heap dump holds through a composition, and the arrays those slots were
   * read out of.
   */
  private class TableSlots(
    /**
     * By the object id of what holds them, as the index of a slot followed by what it points at. Two
     * longs a slot rather than a list of pairs, a composition having as many slots as a UI has state.
     */
    val slotsBySourceId: LongObjectMap<MutableLongList>,
    /** The arrays [slotsBySourceId] accounts for every element of. */
    val tableSlotArrayIds: LongSet
  )

  /** The two arrays one composition is, and how much of each of them it is using. */
  private class TableArrays(
    /** Five ints per group, in the order the composables ran. */
    val groups: IntArray,
    /** Everything every composable of the composition remembered, side by side. */
    val slots: HeapObjectArray,
    val groupCount: Int,
    /** How many slots are in use: the array past this is the gap the next write is made through. */
    val slotCount: Int
  )

  companion object {
    /**
     * What a composition keeps its state in, oldest name first. All of them are the same two arrays
     * described by the same ints, so one reader covers every Compose version that has one — and a
     * version whose table this can't read says so through [SharkLog] rather than reading wrong.
     */
    private val SLOT_TABLE_CLASS_NAMES = listOf(
      "androidx.compose.runtime.SlotTable",
      "androidx.compose.runtime.composer.gapbuffer.SlotTable",
      "androidx.compose.runtime.composer.linkbuffer.SlotTable"
    )

    private const val GROUPS_FIELD_NAME = "groups"

    private const val GROUPS_SIZE_FIELD_NAME = "groupsSize"

    private const val SLOTS_FIELD_NAME = "slots"

    private const val SLOTS_SIZE_FIELD_NAME = "slotsSize"

    /**
     * How a group is described: five ints, of which this needs three — the flags that say whether the
     * group emitted a node, how many groups it contains, and where its slots start.
     *
     * The other two are the key the group was composed under and where its parent is, and the count and
     * the anchor are why this reader is possible at all. Compose treats the lot as private, so expect it
     * to move; what it can't do is stop being a description of the same two arrays.
     */
    private const val GROUP_INT_COUNT = 5

    private const val GROUP_INFO_INT = 1

    private const val GROUP_SIZE_INT = 3

    private const val DATA_ANCHOR_INT = 4

    /** Bit 30 of a group's flags: this group emitted a node, and keeps it in its first slot. */
    private const val NODE_GROUP_MASK = 1 shl 30

    private fun MutableLongObjectMap<MutableLongList>.slotsOf(sourceId: Long): MutableLongList =
      getOrPut(sourceId) { MutableLongList() }

    private fun MutableLongList.addSlot(
      slotIndex: Int,
      valueObjectId: Long
    ) {
      add(slotIndex.toLong())
      add(valueObjectId)
    }
  }
}
