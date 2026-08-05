package shark.explorer

import androidx.collection.IntSet
import androidx.collection.MutableIntSet
import java.util.PriorityQueue
import shark.GcRoot
import shark.GcRoot.Debugger
import shark.GcRoot.Finalizing
import shark.GcRoot.InternedString
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.ReferenceCleanup
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.GcRoot.Unknown
import shark.GcRoot.Unreachable
import shark.GcRoot.VmInternal
import shark.GcRootProvider
import shark.HeapDominatorTree
import shark.HeapField
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HeapValue
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.ObjectDominators.DominatorNode
import shark.ObjectReporter
import shark.AndroidObjectInspectors
import shark.ReferenceLocationType
import shark.SharkLog
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.Companion.NULL_REFERENCE
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder

/**
 * A heap dump's dominator tree, seen as a [TreemapTree] weighted by retained size.
 *
 * Nodes are object ids, and the root is [NULL_REFERENCE]: the virtual root [shark.HeapDominatorTree]
 * puts above every GC root, so that the whole heap dump is one rectangle. Its children are the objects no
 * one object owns, and [UNREACHABLE_NODE_ID] beside them: every object of the dump is in here whether a GC
 * root reaches it or not, and the uncollected garbage is one pile because it has no owner in the reachable
 * heap and its bytes are still bytes.
 *
 * Some node ids stand for a pile of objects rather than for one: the garbage, and one per class the root's
 * or the garbage's children are gathered under. [isPileId] is which, and they're allocated per tree, so
 * they mean nothing to another tree of the same heap dump.
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
// Everything the UI asks about one heap dump: the tree, what a cell of it says, how an object is held, and
// which objects shouldn't be there. One class because every answer reads the same graph, reachability,
// ownership and referrer index, so splitting it means threading all four into a second class that calls
// back into this one. Worth doing, and worth doing on its own rather than inside a change that adds one
// more answer.
@Suppress("LargeClass", "TooManyFunctions")
class HeapDominatorTreemap internal constructor(
  private val graph: HeapGraph,
  private val reachability: HeapReachability,
  private val strengthReader: ReferenceStrengthReader,
  private val ownerReferences: OwnerReferences,
  /** The roots the tree was built from, which is where a path up from an object can end. */
  private val gcRootProvider: GcRootProvider,
  private val dominatorTree: HeapDominatorTree,
  private val nodes: Map<Long, DominatorNode>
) : TreemapTree<Long> {

  override val root: Long get() = NULL_REFERENCE

  /**
   * The root's children, gathered by class, with the uncollected garbage among them as one pile of its
   * own. Computed on first use: it's a pass over every child of the root, and the root of a production
   * dump has six figures worth of them.
   */
  private val topLevel: TopLevel by lazy { splitRootChildren() }

  /**
   * The references this tree was built by following, which is what a path up to a GC root has to follow
   * too: a path through a reference the tree ignored would explain a retention the tree doesn't show.
   */
  private val pathReferenceReader by lazy {
    WeakeningAwareReferenceReader(strengthReader, reachability, ownerReferences)
  }

  /**
   * Which object points at each object, built on first use: the first question about how an object is held
   * pays for a pass over the heap dump, and every question after it is answered from memory.
   */
  private val referrerIndex: ReferrerIndex by lazy {
    ReferrerIndex.buildFor(graph, pathReferenceReader)
  }

  /** Where the pixels of the heap dump's bitmaps come from, and whether it has any. */
  private val bitmaps = HeapBitmaps(graph)

  /**
   * The walk up to the roots the tree was built from, kept between questions: it works over four arrays
   * the size of the heap dump, and the pointer moving across a treemap asks for one path per rectangle it
   * crosses. Built on first use, like the index it walks.
   *
   * Which is when the pass that finds the objects that shouldn't be in memory happens, since a chain avoids
   * them. So the first chain of a heap dump pays for that pass as well as for the index, and the leaks
   * screen is free of it if a chain was drawn first.
   */
  private val rootPathSearch: RootPathSearch by lazy {
    val leakingIndexes = BooleanArray(referrerIndex.objectCount)
    leakingCandidateIds.forEach { objectId ->
      val index = referrerIndex.indexOf(objectId)
      if (index != ReferrerIndex.NOT_AN_OBJECT) {
        leakingIndexes[index] = true
      }
    }
    RootPathSearch(referrerIndex, treeRootIndexes, leakingIndexes)
  }

  /**
   * The objects the tree hangs its halves off, by object index: the GC roots it was built from, and the
   * pieces of garbage nothing else points at. Where a path below either half starts. See
   * [TreeGcRootProvider].
   */
  private val treeRootIndexes: IntSet by lazy {
    val indexes = MutableIntSet()
    gcRootProvider.provideGcRoots(graph).forEach { rootReference ->
      val objectId = rootReference.gcRoot.id
      val index = referrerIndex.indexOf(objectId)
      if (index != ReferrerIndex.NOT_AN_OBJECT) {
        indexes += index
      }
    }
    indexes
  }

  /** Bytes retained by [node]: its own shallow size plus that of everything it dominates. */
  override fun weight(node: Long): Long = group(node)?.retainedSize ?: nodeOf(node).retainedSize

  override fun children(node: Long): List<Long> = when {
    node == root -> topLevel.ids
    else -> group(node)?.childIds ?: nodeOf(node).dominatedObjectIds
  }

  /**
   * This tree's node for [nodeId], which every object of the heap dump has one of.
   *
   * Not [Map.getValue], whose "Key 21474836480 is missing in the map" is what asking this tree about an
   * object of another heap dump reads as, three frames below whatever asked.
   */
  private fun nodeOf(nodeId: Long): DominatorNode = requireNotNull(nodes[nodeId]) {
    "$nodeId is no node of this tree, which has ${nodes.size} of them. Ask contains() first."
  }

  /** Whether [objectId] is a node of this tree, which every object of the heap dump is. */
  operator fun contains(objectId: Long): Boolean = if (isPileId(objectId)) {
    objectId in topLevel.groups
  } else {
    objectId in nodes
  }

  /**
   * What a cell standing for many objects stands for, or null if [node] is one object.
   *
   * Two kinds of cell are a pile of objects. The first is the uncollected garbage: nothing reaches it, so
   * there is no owner to draw it under and no order to draw it in, and one rectangle beside the objects
   * that are still in memory on purpose is the whole of what there is to say about it.
   *
   * The second is a class. The root of a production heap dump has six figures worth of children — every
   * object that more than one thing holds ends up there — and no view can show them one by one. So the
   * root's children are gathered by class, and a class stands in for its instances until you zoom into it.
   * Only there and under the garbage, and only past [MIN_CHILDREN_TO_GROUP_BY_CLASS] children: elsewhere in
   * the tree a node's children are what holds what, and replacing them with classes would throw that away;
   * at the top there is nothing to throw away, because being there means nothing owns you.
   */
  fun groupOrNull(node: Long): ObjectGroupSummary? = group(node)?.let { group ->
    ObjectGroupSummary(
      nodeId = node,
      kind = group.kind,
      strength = group.strength,
      className = group.className,
      objectCount = group.objectCount,
      retainedSize = group.retainedSize
    )
  }

  private fun group(node: Long): NodeGroup? =
    if (isPileId(node)) topLevel.groups[node] else null

  /**
   * Gathers the root's children by class, and puts the uncollected garbage among them as one pile.
   *
   * The reachable side is no level of its own: its children *are* the root's children, so that the root of
   * the map is the whole heap dump wherever you are rather than a rectangle you have to go through to see
   * anything. Which leaves the garbage a sibling of the objects that are still in memory on purpose, drawn
   * where its size puts it like any other child.
   */
  private fun splitRootChildren(): TopLevel {
    val reachable = TopLevelHalf(
      strength = ReachabilityStrength.STRONG,
      objectCount = reachability.sizes.reachableObjectCount
    )
    val unreachable = TopLevelHalf(
      strength = ReachabilityStrength.UNREACHABLE,
      objectCount = reachability.sizes.unreachableObjectCount
    )
    // One pass, one read of each child: a production dump has six figures worth of them, and looking one
    // up twice — once for its strength and once for its class — was seconds of the wait to first paint.
    nodeOf(root).dominatedObjectIds.forEach { objectId ->
      val heapObject = graph.findObjectById(objectId)
      val half = if (reachability.strengthOf(heapObject) == ReachabilityStrength.UNREACHABLE) {
        unreachable
      } else {
        reachable
      }
      half.add(heapObject, nodeOf(objectId).retainedSize)
    }
    val groups = LinkedHashMap<Long, NodeGroup>()
    val groupIds = GroupIds()
    val ids = groupedChildIds(reachable, parentNodeId = root, groups, groupIds).toMutableList()
    // Only when there is some: a dump whose garbage was all collected shouldn't grow a rectangle saying so.
    if (unreachable.childIds.isNotEmpty()) {
      ids += UNREACHABLE_NODE_ID
      groups[UNREACHABLE_NODE_ID] = NodeGroup(
        kind = ObjectGroupKind.UNREACHABLE,
        parentNodeId = root,
        strength = unreachable.strength,
        childIds = groupedChildIds(unreachable, UNREACHABLE_NODE_ID, groups, groupIds),
        retainedSize = unreachable.retainedSize,
        objectCount = unreachable.objectCount
      )
    }
    // Heaviest first, like the dominated ids a node hands out, so that the root's children are ordered the
    // way every other level's are. Not through [weight], which would ask for the groups being built.
    ids.sortByDescending { groups[it]?.retainedSize ?: nodeOf(it).retainedSize }
    val classGroupIdByObjectId = mutableMapOf<Long, Long>()
    groups.forEach { (groupId, group) ->
      if (group.kind == ObjectGroupKind.CLASS) {
        group.childIds.forEach { classGroupIdByObjectId[it] = groupId }
      }
    }
    return TopLevel(ids = ids, groups = groups, classGroupIdByObjectId = classGroupIdByObjectId)
  }

  /**
   * What one half's children are drawn as, heaviest first: gathered by class once there are more of them
   * than a view can show one by one, and as they are below that. Adds a group per class to [groups].
   */
  private fun groupedChildIds(
    half: TopLevelHalf,
    parentNodeId: Long,
    groups: MutableMap<Long, NodeGroup>,
    groupIds: GroupIds
  ): List<Long> {
    val grouped = if (half.childIds.size <= MIN_CHILDREN_TO_GROUP_BY_CLASS) {
      half.childIds
    } else {
      groupByClass(half, parentNodeId, groups, groupIds)
    }
    return grouped.sortedByDescending { groups[it]?.retainedSize ?: nodeOf(it).retainedSize }
  }

  /**
   * What one half's children become once gathered by class: a group id per class with more than one
   * instance in it, and the other ids as they were. Adds a group per class to [groups].
   */
  private fun groupByClass(
    half: TopLevelHalf,
    parentNodeId: Long,
    groups: MutableMap<Long, NodeGroup>,
    groupIds: GroupIds
  ): List<Long> {
    val ids = half.ungroupedIds.toMutableList()
    half.idsByClassId.forEach { (classId, objectIds) ->
      // A class with one instance here is that instance. Wrapping it in a group of one would add a
      // rectangle that says nothing and a level to click through.
      if (objectIds.size == 1) {
        ids += objectIds
      } else {
        val heapClass = checkNotNull(graph.findObjectById(classId).asClass) {
          "The class ${objectIds.size} objects were gathered under, ${hexObjectId(classId)}, is no " +
            "class of the heap dump"
        }
        val groupId = groupIds.next()
        groups[groupId] = NodeGroup(
          kind = ObjectGroupKind.CLASS,
          parentNodeId = parentNodeId,
          className = heapClass.name,
          simpleClassName = heapClass.simpleName,
          strength = half.strength,
          childIds = objectIds,
          retainedSize = objectIds.sumOf { nodeOf(it).retainedSize },
          objectCount = objectIds.size
        )
        ids += groupId
      }
    }
    return ids
  }

  /**
   * The class an object is grouped under, or null for one that isn't grouped: a class object, unless the
   * dump has `java.lang.Class` for them all to gather under, which every Android heap dump does.
   */
  private fun HeapObject.groupingClassId(): Long? = when (this) {
    is HeapInstance -> instanceClassId
    is HeapObjectArray -> arrayClassId
    // Not [HeapPrimitiveArray.arrayClass], which goes through findClassByName every time it's asked.
    is HeapPrimitiveArray -> classIdOf(arrayClassName)
    is HeapClass -> classIdOf(JAVA_LANG_CLASS)
  }

  /**
   * The id of a class by name, looked up once per name and remembered whether it was found or not:
   * [HeapGraph.findClassByName] performs two linear scans over every string of the heap dump, and asking
   * it per object of a production dump — once for every class object, once for every `byte[]` — took the
   * best part of a minute.
   */
  private fun classIdOf(className: String): Long? {
    if (className in classIdByName) {
      return classIdByName[className]
    }
    val classId = graph.findClassByName(className)?.objectId
    if (classId == null) {
      // Which leaves every object of that class as a rectangle of its own under the root rather than
      // gathered with its siblings, so it's worth a line saying so.
      SharkLog.d { "No class named $className in the heap dump, so nothing groups by it" }
    }
    classIdByName[className] = classId
    return classId
  }

  private val classIdByName = mutableMapOf<String, Long?>()

  /** How strongly the garbage collector holds on to [node], or to what a group of objects holds. */
  fun strengthOf(node: Long): ReachabilityStrength = when {
    node == root -> ReachabilityStrength.STRONG
    else -> group(node)?.strength ?: reachability.strengthOf(node)
  }

  /**
   * A short name for [node], to draw on its rectangle.
   *
   * Cheap enough to call for every visible rectangle, unlike [summarize].
   */
  fun label(node: Long): String {
    if (node == root) {
      return ROOT_LABEL
    }
    val group = group(node)
    if (group != null) {
      return group.label()
    }
    return graph.findObjectById(node).cellLabel()
  }

  private fun NodeGroup.label(): String = when (kind) {
    ObjectGroupKind.UNREACHABLE -> UNREACHABLE_LABEL
    // "42 × Bitmap" rather than "Bitmap": a count and a multiplication sign say this cell is a pile
    // of objects and not one of them, on a rectangle with room for nothing else.
    ObjectGroupKind.CLASS -> "$objectCount $CLASS_GROUP_LABEL_SEPARATOR $simpleClassName"
  }

  private fun HeapObject.cellLabel(): String = when (this) {
    is HeapClass -> "class $simpleName"
    is HeapInstance -> instanceClassSimpleName
    is HeapObjectArray -> arrayClassSimpleName
    is HeapPrimitiveArray -> arrayClassName
  }

  /**
   * Everything the details panel shows about [objectId].
   *
   * Reads the object and runs Shark's object inspectors over it, so call it for the selected object
   * rather than for every rectangle.
   */
  fun summarize(objectId: Long): HeapObjectSummary {
    require(group(objectId) == null) {
      "$objectId stands for a pile of objects rather than for one. Ask groupOrNull() first, and " +
        "describe it with what that returns."
    }
    val node = nodeOf(objectId)
    val heapObject = if (objectId == root) null else graph.findObjectById(objectId)
    val fields = heapObject?.fieldsOf() ?: FieldList(emptyList(), totalCount = 0)
    return HeapObjectSummary(
      objectId = objectId,
      label = label(objectId),
      className = heapObject?.className() ?: ROOT_LABEL,
      kind = heapObject?.kind(),
      headline = heapObject?.headline(),
      strength = strengthOf(objectId),
      shallowSize = node.shallowSize,
      retainedSize = node.retainedSize,
      retainedCount = node.retainedCount,
      dominatedObjectCount = node.dominatedObjectIds.size,
      inspectorLabels = heapObject?.inspect()?.labels?.toList() ?: emptyList(),
      fields = fields.shown,
      hiddenFieldCount = fields.totalCount - fields.shown.size
    )
  }

  /**
   * What Shark's inspectors have to say about an object: labels to draw beside it, e.g. that an activity
   * is destroyed, and whether it is meant to still be in memory.
   *
   * Both halves every time, because they are the same read: the inspectors work out the reasons on the way
   * to the labels, and a path that kept only the labels would be one heap dump read away from knowing
   * whether the objects on it are leaking. See [LeakStatus].
   */
  private fun HeapObject.inspect(): ObjectReporter {
    val reporter = ObjectReporter(this)
    AndroidObjectInspectors.appDefaults.forEach { it.inspect(reporter) }
    return reporter
  }

  /**
   * The objects of the heap dump [filter] matches, the largest [limit] of them, largest first.
   *
   * The whole heap dump as a list, which is the view a treemap can't be: a class with a thousand small
   * instances is one line here and a thousand rectangles too small to draw there. Sizes come from the same
   * nodes the treemap draws, so the two agree to the byte.
   *
   * A pass over every object of the dump, which is seconds on a large one, so it belongs on the heap
   * dump's thread like everything else here. Only the shown entries are read past their class name.
   */
  fun listObjects(
    filter: ObjectListFilter,
    limit: Int = MAX_LISTED_OBJECTS
  ): ObjectList {
    var matchCount = 0
    // The largest matches so far, smallest of them first, so that the one to drop is the head: a dump has
    // millions of objects and a list shows a few hundred, and sorting every match would cost more memory
    // than the tree does.
    val largest = PriorityQueue<Match>(limit + 1, compareBy { it.retainedSize })
    nodes.forEach { (objectId, node) ->
      if (objectId == root) {
        return@forEach
      }
      val heapObject = graph.findObjectByIdOrNull(objectId) ?: return@forEach
      // The class name comes from the index rather than from the record, which is what makes matching on
      // it affordable for every object of the dump.
      if (!filter.matches(heapObject.className(), heapObject.kind())) {
        return@forEach
      }
      matchCount++
      if (largest.size == limit && node.retainedSize <= largest.peek().retainedSize) {
        return@forEach
      }
      largest += Match(objectId, node.retainedSize, node.shallowSize)
      if (largest.size > limit) {
        largest.poll()
      }
    }
    return ObjectList(
      filter = filter,
      entries = largest.sortedByDescending { it.retainedSize }.map { it.entry() },
      matchCount = matchCount,
      // The virtual root is a node of the tree and no object of the heap dump.
      totalCount = nodes.size - 1
    )
  }

  /** One object a filter matched, before the read that turns it into a row. */
  private class Match(
    val objectId: Long,
    val retainedSize: Long,
    val shallowSize: Long
  )

  private fun Match.entry(): ObjectListEntry {
    val heapObject = graph.findObjectById(objectId)
    return ObjectListEntry(
      objectId = objectId,
      className = heapObject.className(),
      kind = heapObject.kind(),
      headline = heapObject.headline(),
      shallowSize = shallowSize,
      retainedSize = retainedSize,
      strength = reachability.strengthOf(heapObject)
    )
  }

  private fun HeapObject.className(): String = when (this) {
    is HeapClass -> name
    is HeapInstance -> instanceClassName
    is HeapObjectArray -> arrayClassName
    is HeapPrimitiveArray -> arrayClassName
  }

  private fun HeapObject.kind(): HeapObjectKind = when (this) {
    is HeapClass -> HeapObjectKind.CLASS
    is HeapInstance -> HeapObjectKind.INSTANCE
    is HeapObjectArray -> HeapObjectKind.OBJECT_ARRAY
    is HeapPrimitiveArray -> HeapObjectKind.PRIMITIVE_ARRAY
  }

  /**
   * The one node this tree attributes [objectId]'s bytes to, or null for the root itself and for an
   * object that is no node of the tree.
   *
   * The dominator is the answer to "what would free this": every path from a GC root to the object goes
   * through it. There is exactly one, which is what makes it worth showing on its own, and it is a group
   * rather than an object when nothing in particular holds the object — see [DominatorKind].
   */
  fun dominatorOf(objectId: Long): ObjectDominator? {
    if (objectId == root || objectId !in nodes) {
      return null
    }
    val dominatorId = dominatorTree.immediateDominatorOf(objectId)
    if (dominatorId != root) {
      return ObjectDominator(
        nodeId = dominatorId,
        label = label(dominatorId),
        retainedSize = weight(dominatorId),
        kind = DominatorKind.OBJECT
      )
    }
    // Nothing owns it, so where the tree draws it is what stands in for a dominator: in the pile of
    // garbage, or directly under the whole heap dump, which is where an object no one thing holds goes.
    val isGarbage = strengthOf(objectId) == ReachabilityStrength.UNREACHABLE
    val nodeId = if (isGarbage) UNREACHABLE_NODE_ID else root
    return ObjectDominator(
      nodeId = nodeId,
      label = label(nodeId),
      retainedSize = weight(nodeId),
      kind = if (isGarbage) {
        DominatorKind.UNCOLLECTED_GARBAGE
      } else {
        DominatorKind.WHOLE_HEAP_DUMP
      }
    )
  }

  /**
   * Every way [toObjectId] is held below [fromObjectId], spelled out field by field. See
   * [IndependentPaths] for what "independent" means and what this search does and doesn't guarantee.
   *
   * Asked of the two ends of a stretch of a chain that isn't forced: the steps between two objects that
   * both dominate [toObjectId] are only one of the ways the lower one is reached from the upper, and these
   * are the rest of them. See [RootPathDetour].
   *
   * At most [MAX_INDEPENDENT_PATHS] of them, each at most [MAX_PATH_STEPS] steps long. The first call for
   * a heap dump builds a [ReferrerIndex], which reads the whole dump; the paths themselves are then walks
   * in memory, so the wait is once per dump rather than once per object.
   */
  fun independentPathsBetween(
    fromObjectId: Long,
    toObjectId: Long
  ): IndependentPaths {
    val fromIndex = referrerIndex.indexOf(fromObjectId)
    if (fromIndex == ReferrerIndex.NOT_AN_OBJECT) {
      SharkLog.d {
        "No paths from ${hexObjectId(fromObjectId)}: it is no object of the heap dump"
      }
      return IndependentPaths.NONE
    }
    return independentPathsTo(toObjectId, isBelowGroup = false) { index -> index == fromIndex }
  }

  /**
   * And every way [toObjectId] is held from where the tree's own walk started: a GC root, or a piece of
   * garbage nothing else points at.
   *
   * What the top of a chain is asked, since nothing above it holds it. A bitmap under the root turns out to
   * be held by the view showing it on one path and by an image cache on another: the view is the answer
   * anyone is after, and the cache is why the dominator tree had nowhere to put its bytes but the whole
   * heap.
   */
  fun independentPathsFromRoots(toObjectId: Long): IndependentPaths =
    independentPathsTo(toObjectId, isBelowGroup = true) { index -> index in treeRootIndexes }

  private fun independentPathsTo(
    toObjectId: Long,
    /** Whether a path starts at a GC rooted object, which is then a step of it rather than left out. */
    isBelowGroup: Boolean,
    isSource: (Int) -> Boolean
  ): IndependentPaths {
    if (toObjectId == root || toObjectId !in nodes) {
      return IndependentPaths.NONE
    }
    val targetIndex = referrerIndex.indexOf(toObjectId)
    if (targetIndex == ReferrerIndex.NOT_AN_OBJECT) {
      // A node of the tree the heap dump has no object for: nothing to walk up from, and the tree and
      // the dump disagreeing about what is in the dump.
      SharkLog.d {
        "No paths to ${hexObjectId(toObjectId)}: it is a node of the tree and no object of the heap dump"
      }
      return IndependentPaths.NONE
    }
    val paths = mutableListOf<IndependentPath>()
    // A GC root's own object, or a piece of garbage nothing points at: what holds it is the root or nothing
    // at all, and walking up its referrers can't say so, because it has none.
    if (isSource(targetIndex)) {
      paths += path(intArrayOf(targetIndex), isBelowGroup)
    }
    val search = PathSearch(targetIndex)
    while (paths.size < MAX_INDEPENDENT_PATHS) {
      val found = search.findPath(isSource) ?: break
      paths += path(found, isBelowGroup)
    }
    if (paths.isEmpty()) {
      // Asked between two objects one of which dominates the other, or from the roots the tree was walked
      // from, so there is a path by construction. Not finding one means the walk up the referrers and the
      // walk down the tree were built through different references, which shows as a chain the window says
      // has no alternative.
      SharkLog.d { "No path found down to ${hexObjectId(toObjectId)}, though something above holds it" }
    }
    return IndependentPaths(paths = paths, hasMore = paths.size == MAX_INDEPENDENT_PATHS)
  }

  /**
   * Reads the heap dump to work out which object points at which, and says how many objects that
   * covered.
   *
   * A pass over the whole dump, seconds on a large one, paid once and then answered from memory: every
   * question about how an object is held walks this. Worth calling before the questions start, because
   * otherwise the first of them is the one that waits for it — and the first of them is now the pointer
   * moving over a rectangle.
   */
  fun indexReferrers(): Int = referrerIndex.objectCount

  /**
   * The shortest way a GC root reaches [objectId], or [RootPath.NONE] when nothing the tree was built
   * from does.
   *
   * Shortest in steps, over the same references the tree was built by, so it is the plainest answer to
   * "how is this held" — and the steps that dominate [objectId] are marked, which is what ties the chain
   * back to the rectangle the treemap draws it in. Every path from a GC root goes through every one of
   * those dominators, so they are always all on it.
   *
   * Cheap enough to ask as the pointer moves, once [indexReferrers] has been paid for: one breadth first
   * walk over as much of the graph as it takes to reach a root, and only the steps that end up shown are
   * read out of the heap dump. At most [MAX_ROOT_PATH_STEPS] of them.
   */
  fun rootPathTo(objectId: Long): RootPath = rootPathAlong(rootPathObjectIdsTo(objectId))

  /**
   * That same chain as object ids, the whole of it, from the GC rooted object down to [objectId]. Empty
   * when nothing the tree was built from reaches it.
   *
   * The walk is what finding a chain costs, and reading a step out of the heap dump is what showing one
   * does, so the walk is separate: a question about the whole chain that ids alone answer — whether another
   * leak is above this object on it — is asked here rather than of the steps that ended up drawn.
   */
  private fun rootPathObjectIdsTo(objectId: Long): List<Long> {
    if (objectId == root || objectId !in nodes) {
      return emptyList()
    }
    val targetIndex = referrerIndex.indexOf(objectId)
    if (targetIndex == ReferrerIndex.NOT_AN_OBJECT) {
      // A node of the tree the heap dump has no object for: nothing to walk up from, and the tree and the
      // dump disagreeing about what is in the dump.
      SharkLog.d {
        "No path to ${hexObjectId(objectId)}: it is a node of the tree and no object of the heap dump"
      }
      return emptyList()
    }
    val found = rootPathSearch.findPath(targetIndex)
    if (found == null) {
      // The tree hangs every object off one of the roots it walked from, so there is a path by
      // construction. Not finding one means this walk and that one followed different references, which
      // shows as the panel saying nothing reaches an object the treemap draws.
      SharkLog.d {
        "No path from a GC root down to ${hexObjectId(objectId)}, though the tree hangs it off one"
      }
      return emptyList()
    }
    return found.map { referrerIndex.objectIdAt(it) }
  }

  /** The steps of [pathObjectIds] worth drawing, read out of the heap dump. See [rootPathTo]. */
  private fun rootPathAlong(pathObjectIds: List<Long>): RootPath {
    if (pathObjectIds.isEmpty()) {
      return RootPath.NONE
    }
    // Kept from the object up, like an independent path: what holds it directly is what a reader is
    // after, and the plumbing between a GC root and an app's own objects rarely is.
    val hiddenStepCount = (pathObjectIds.size - MAX_ROOT_PATH_STEPS).coerceAtLeast(0)
    // One before the first shown step, when there is one: it names the field that step is held in.
    val fromIndex = (hiddenStepCount - 1).coerceAtLeast(0)
    val objectIds = pathObjectIds.subList(fromIndex, pathObjectIds.size)
    val dominatorIds = dominatorIdsOf(pathObjectIds.last())
    val steps = objectIds.mapIndexedNotNull { index, stepObjectId ->
      when {
        index > 0 -> stepTo(stepObjectId, referrerId = objectIds[index - 1])
        // The GC root's own object, which no field of the heap dump points at. Only when the whole chain
        // is shown: otherwise this is the last of the objects left out, here to name that field.
        hiddenStepCount == 0 -> step(stepObjectId, reference = null)
        else -> null
      }
    }
    return RootPath(
      gcRootLabel = gcRootLabelOf(pathObjectIds.first()),
      steps = steps.withLeakStatuses()
        .map { RootPathStep(it, isDominator = it.objectId in dominatorIds) },
      hiddenStepCount = hiddenStepCount
    )
  }

  /**
   * Every step of [pathObjectIds], read out of the heap dump, where [rootPathAlong] reads the last
   * [MAX_ROOT_PATH_STEPS] of them. For the questions a leak asks of a chain, which are about all of it.
   */
  private fun stepsAlong(pathObjectIds: List<Long>): List<PathStep> =
    pathObjectIds.mapIndexed { index, stepObjectId ->
      if (index == 0) {
        // The GC root's own object, which no field of the heap dump points at.
        step(stepObjectId, reference = null)
      } else {
        stepTo(stepObjectId, referrerId = pathObjectIds[index - 1])
      }
    }.withLeakStatuses()

  /** The objects that dominate [objectId], which every path from a GC root down to it goes through. */
  private fun dominatorIdsOf(objectId: Long): Set<Long> {
    val dominatorIds = mutableSetOf<Long>()
    var current = dominatorTree.immediateDominatorOf(objectId)
    while (current != root) {
      dominatorIds += current
      current = dominatorTree.immediateDominatorOf(current)
    }
    return dominatorIds
  }

  /**
   * Every object of the heap dump that shouldn't be there, gathered into the leaks they are instances of.
   *
   * Found the two ways Shark finds them, because they answer different questions and a heap dump usually
   * has both. **What the app said**: an object handed to LeakCanary's `ObjectWatcher` — a destroyed
   * activity, a cleared view model — that a garbage collection didn't take. **What the framework says**:
   * an object Shark's inspectors recognize as one that shouldn't be in memory whether or not anything was
   * watching it, an activity whose `mDestroyed` is true being the plainest of them. Neither finds what the
   * other does, so the list is both.
   *
   * A pass over every instance of the heap dump with a couple of dozen filters on it, and a walk up to the
   * GC roots per object found, so this is seconds on a large dump — it belongs behind a screen someone
   * asked for, like the list of every object, rather than anywhere near the pointer. Worked out once per
   * heap dump and kept.
   */
  fun findLeaks(): HeapLeaks = leaks

  private val leaks: HeapLeaks by lazy { computeLeaks() }

  /** The same thing as a set, kept because shading the treemap by it asks per rectangle drawn. */
  private val leakingObjectIds: Set<Long> by lazy { leaks.leakingObjectIds }

  /**
   * Whether a leaking object dominates [node], which makes everything drawn inside it leaking too:
   * every path from a GC root to these objects goes through one that shouldn't be there, so they are only
   * still in memory because it is.
   *
   * What the treemap shades leaks by, together with the ids themselves — a rectangle is drawn inside the
   * one that dominates it, so the map only has to be told about the object at the top of each leak, and
   * this is for the view that is already rooted inside one.
   */
  fun isBelowLeakingObject(node: Long): Boolean {
    if (leakingObjectIds.isEmpty()) {
      return false
    }
    // Nothing dominates the root, a pile stands for many objects rather than one, and an object this tree
    // has no node for is one of another heap dump: none of the three has a walk up to make.
    if (node == root || isPileId(node) || node !in nodes) {
      return false
    }
    var current = dominatorTree.immediateDominatorOf(node)
    while (current != root) {
      if (current in leakingObjectIds) {
        return true
      }
      current = dominatorTree.immediateDominatorOf(current)
    }
    return false
  }

  /** What LeakCanary's watcher was left holding, read once: which of them are leaks takes a chain. */
  private val watchers: Map<Long, WatchedObject> by lazy { WatchedObjects.readFrom(graph) }

  /**
   * The objects that shouldn't be in memory, before anything is known about how they are held.
   *
   * Its own pass because both the leaks screen and every chain drawn in the window want it, and it costs a
   * read of every instance of the heap dump — see [leakingObjectIds] and [rootPathSearch].
   */
  private val leakingCandidateIds: List<Long> by lazy {
    val startNanos = System.nanoTime()
    val ids = leakingObjectIds(watchers)
    SharkLog.d { "Found ${ids.size} objects that shouldn't be here in ${millisSince(startNanos)} ms" }
    ids
  }

  private fun computeLeaks(): HeapLeaks {
    val startNanos = System.nanoTime()
    val candidateIds = leakingCandidateIds
    // Largest first, and capped: the walk up to the GC roots per object is what this costs, and a heap
    // dump with thousands of leaking objects has a handful of leaks with thousands of instances each.
    val found = candidateIds
      .sortedByDescending { nodes[it]?.retainedSize ?: 0L }
      .take(MAX_LEAKING_OBJECTS)
      .map { objectId -> foundLeak(objectId, watchers[objectId]) }
      .foldedIntoWhatHoldsThem()
    if (candidateIds.size > MAX_LEAKING_OBJECTS) {
      SharkLog.d {
        "Only the largest $MAX_LEAKING_OBJECTS of the ${candidateIds.size} leaking objects are listed"
      }
    }
    val sections = LeakKind.values().map { kind ->
      LeakSection(kind = kind, groups = found.filter { it.kind == kind }.groups())
    }
    SharkLog.d {
      "Found ${found.size} leaking objects in ${millisSince(startNanos)} ms: " +
        sections.joinToString { "${it.objectCount} ${it.kind.title.lowercase()}" }
    }
    return HeapLeaks(sections)
  }

  /**
   * The objects two of Shark's ways of finding leaks agree there is something wrong with, as ids.
   *
   * The watched ones come from the weak references LeakCanary left behind, and only the retained ones: a
   * watched object the watcher decided was collected in time isn't a leak. The rest come from a pass with
   * the object inspectors' own filters, over the instances of the dump rather than every object of it,
   * because every one of those filters is about an instance of an Android class.
   */
  private fun leakingObjectIds(watchers: Map<Long, WatchedObject>): List<Long> {
    val leakingIds = watchers.values.filter { it.isRetained }.mapTo(LinkedHashSet()) {
      it.referentObjectId
    }
    val filters = AndroidObjectInspectors.appLeakingObjectFilters
    graph.instances.forEach { instance ->
      if (instance.objectId !in leakingIds && filters.any { it.isLeakingObject(instance) }) {
        leakingIds += instance.objectId
      }
    }
    return leakingIds.toList()
  }

  /** Which leak one leaking object is an instance of, which takes walking up to the GC roots. */
  private fun foundLeak(
    objectId: Long,
    watcher: WatchedObject?
  ): FoundLeak {
    val strength = strengthOf(objectId)
    // Nothing holds it, so there is no chain to read and nothing to group by but its class: it is a leak
    // that has already been collected, or that the collection before the dump was written got to.
    val pathObjectIds = if (strength == ReachabilityStrength.UNREACHABLE) {
      emptyList()
    } else {
      rootPathObjectIdsTo(objectId)
    }
    // The whole chain rather than the part of it a pane draws, since a leak is named and grouped by the
    // stretch of it between the last object still needed and the first that shouldn't be there, and
    // cutting the top off a chain moves that stretch.
    val steps = stepsAlong(pathObjectIds)
    val target = steps.lastOrNull()
    // The last step of the chain is this object, already read while the chain was. Only a leak with no
    // chain to read it off is read here, which is why this is read on demand.
    val heapObject by lazy { graph.findObjectById(objectId) }
    val leakingObject = LeakingObject(
      objectId = objectId,
      className = target?.className ?: heapObject.className(),
      kind = target?.kind ?: heapObject.kind(),
      headline = target?.headline ?: heapObject.headline(),
      retainedSize = nodes[objectId]?.retainedSize ?: 0L,
      retainedCount = nodes[objectId]?.retainedCount ?: 0,
      strength = strength,
      leakingReason = target?.leakStatusReason?.takeIf { target.leakStatus == LeakStatus.LEAKING },
      watcher = watcher
    )
    val simpleClassName = leakingObject.className.substringAfterLast('.')
    if (steps.isEmpty()) {
      return FoundLeak(
        kind = LeakKind.UNREACHABLE,
        // Its class, since there is no chain to hash and nothing else to tell two of these apart. LeakCanary
        // has no signature to match here: it reports what a GC root reaches, and this is what none does.
        signature = leakingObject.className.sha1Hex(),
        title = simpleClassName,
        heldBy = null,
        subtitle = UNREACHABLE_LEAK_SUBTITLE,
        leakingObject = leakingObject
      )
    }
    // Both ends of it: a leak is named after the reference that shouldn't be holding, and the other end is
    // what the object that leaked hangs off, which is the one to go looking for on the chain.
    val suspectSubpath = suspectSubpath(steps)
    // The first one on the way down, so a chain through two known leaks is named after the one nearest
    // the root, which is the one holding the other. A chain goes through a known leaking reference only
    // when there is no other way to the object, which is LeakCanary's rule and is what puts the same
    // leaks in this section as it puts in its own library leak list. See [RootPathSearch].
    val libraryLeak = steps.firstNotNullOfOrNull { it.reference?.libraryLeak }
    if (libraryLeak != null) {
      return FoundLeak(
        kind = LeakKind.LIBRARY,
        // Which is what `shark.LibraryLeak.signature` hashes, so a library leak of a LeakCanary report and
        // one of this list are the same string when they are the same known leak.
        signature = libraryLeak.pattern.sha1Hex(),
        title = libraryLeak.pattern,
        heldBy = suspectSubpath.lastOrNull(),
        subtitle = libraryLeak.description.ifEmpty { null },
        leakingObject = leakingObject
      )
    }
    return FoundLeak(
      kind = LeakKind.APPLICATION,
      // Which is the rule LeakCanary groups leaks by, run by LeakCanary's own code over this chain: two
      // objects reached through the same suspect stretch of references are two instances of one leak,
      // whatever their classes and however far below it they are.
      signature = steps.leakSignature(),
      // The reference that shouldn't be holding, which is the leak itself rather than one of the objects
      // it left behind. Those are the rows under it, and each says what it is.
      title = suspectSubpath.firstOrNull() ?: simpleClassName,
      heldBy = suspectSubpath.lastOrNull(),
      subtitle = leakingObject.leakingReason,
      leakingObject = leakingObject
    )
  }

  /**
   * The suspect stretch of a chain, as `Class.field` per reference: how the last object known to still be
   * needed reaches the first one that shouldn't be there. What a leak is named after, since the first of
   * them is the reference that shouldn't be holding.
   *
   * What everything leaking for one reason has in common. The references below the first leaking object are
   * left out because they are what the leak is holding rather than why: a leaked activity holds its window,
   * its view tree and its bitmaps, and those are the same leak whether you land on the activity or on a
   * bitmap eight references under it. The references above the last one known to be needed are left out for
   * the other reason — they are the app working as intended.
   *
   * The same stretch [leakSignature] hashes, spelled the way the chain pane spells a step rather than the
   * way `LeakTrace.signature` spells one: by the class that declares the field, so that the name of a leak
   * is a string that is also on the chain drawn for it. Which is why the name is no substitute for the
   * signature and the two are both on the row.
   */
  private fun suspectSubpath(steps: List<PathStep>): List<String> {
    val lastNotLeaking = steps.indexOfLast { it.leakStatus == LeakStatus.NOT_LEAKING }
    val firstLeaking = steps.indexOfFirst { it.leakStatus == LeakStatus.LEAKING }
      .let { if (it == -1) steps.lastIndex else it }
    return (lastNotLeaking + 1..firstLeaking)
      .mapNotNull { steps[it].reference }
      .map { it.genericLabel() }
  }

  /**
   * A reference spelled the way the chain pane spells it — `Tile.view`, `Object[][x]` — with an array index
   * erased, since which slot an object is in is no part of what makes a leak that leak.
   *
   * Spelled that way because a leak is named after one of these, and the name is only worth anything if it
   * is the same string as the step someone then goes looking for on the chain.
   */
  private fun PathReference.genericLabel(): String = when (locationType) {
    ReferenceLocationType.ARRAY_ENTRY -> "$ownerClassName[x]"
    ReferenceLocationType.LOCAL -> "$ownerClassName.$LOCAL_VARIABLE"
    ReferenceLocationType.INSTANCE_FIELD, ReferenceLocationType.STATIC_FIELD -> "$ownerClassName.$name"
  }

  /** One leaking object and which leak it is an instance of, before the instances are gathered. */
  private class FoundLeak(
    val kind: LeakKind,
    /** What makes two objects instances of the same leak. See [LeakGroup.signature]. */
    val signature: String,
    val title: String,
    val heldBy: String?,
    val subtitle: String?,
    val leakingObject: LeakingObject
  )

  /**
   * Leaks another leak dominates, dropped from the list.
   *
   * A leaked activity holds a leaked window which holds a leaked view, and each of the three is an object
   * that shouldn't be in memory — but there is one thing to fix, and it is the one nearest the GC roots:
   * let go of the activity and the other two go with it.
   *
   * **Dominates, rather than "is on the chain drawn for it".** The claim being made about a folded row is
   * that fixing the leak above it fixes it too, and that is only true when every way to it goes through
   * that leak, which is what dominating is. An object a leak holds *one* of the ways to is an object that
   * would still be there once that leak is gone, so it is a leak of its own and belongs on the list. Which
   * also makes the list stable: whether one leak is under another is a property of the heap dump rather
   * than of which of several equally good chains [rootPathTo] happened to pick.
   *
   * Nothing is lost by folding. Every one of them is still on the map, shaded as leaking like everything
   * else a leak holds, and the chain drawn for one runs through the leak it was folded into and says that
   * one is leaking and why — which is the chain being read as a leak trace.
   */
  private fun List<FoundLeak>.foldedIntoWhatHoldsThem(): List<FoundLeak> {
    val leakingIds = mapTo(mutableSetOf()) { it.leakingObject.objectId }
    val kept = filter { found ->
      dominatorIdsOf(found.leakingObject.objectId).none { it in leakingIds }
    }
    if (kept.size < size) {
      SharkLog.d {
        "${size - kept.size} of the $size leaking objects are dominated by another one, listed under it"
      }
    }
    return kept
  }

  /** The leaks a list of leaking objects amounts to, largest first, and their objects largest first. */
  private fun List<FoundLeak>.groups(): List<LeakGroup> =
    groupBy { it.signature }
      .map { (signature, found) ->
        LeakGroup(
          signature = signature,
          title = found.first().title,
          heldBy = found.first().heldBy,
          // From whichever object was recognized first, since a leak is one thing however many objects
          // of it there are: they are all leaking for the same reason, which is what grouped them.
          subtitle = found.firstNotNullOfOrNull { it.subtitle },
          objects = found.map { it.leakingObject }.sortedByDescending { it.retainedSize }
        )
      }
      .sortedByDescending { it.retainedSize }

  /**
   * The nodes to zoom through so that [node] is drawn, the root first.
   *
   * Clicking an object in the details panel should show it where the treemap has it, which is under
   * however many groups and dominators it sits — the panel walks the heap dump's own references, so what
   * it leads to can be anywhere in the tree.
   *
   * Stops at the last node that has children: zooming into an object that dominates nothing would draw an
   * empty view, so the caller ends up looking at what holds it, with it selected inside.
   */
  fun pathToOpen(node: Long): List<Long> {
    if (node == root) {
      return listOf(root)
    }
    val group = group(node)
    if (group != null) {
      // A class group of the garbage hangs off the garbage pile; everything else at the top hangs off the
      // root, which the path starts at anyway.
      val above = if (group.parentNodeId == root) listOf(root) else listOf(root, group.parentNodeId)
      return above + listOfNotNull(node.takeIf { group.childIds.isNotEmpty() })
    }
    if (node !in nodes) {
      return listOf(root)
    }
    val dominators = ArrayDeque<Long>()
    var current = node
    while (current != root) {
      dominators.addFirst(current)
      current = dominatorTree.immediateDominatorOf(current)
    }
    if (children(node).isEmpty()) {
      dominators.removeLast()
    }
    val topLevelObjectId = dominators.firstOrNull() ?: node
    return listOf(root) +
      listOfNotNull(garbagePileOrNull(topLevelObjectId)) +
      listOfNotNull(topLevel.classGroupIdByObjectId[topLevelObjectId]) +
      dominators
  }

  /** The pile the garbage is drawn as, for an object in it, and null for one the GC roots reach. */
  private fun garbagePileOrNull(objectId: Long): Long? =
    UNREACHABLE_NODE_ID.takeIf { strengthOf(objectId) == ReachabilityStrength.UNREACHABLE }

  /**
   * One found path as the UI shows it: from the step below where it starts down to the object.
   *
   * Below a group the first step is the GC rooted object itself, named by the kind of root that reaches
   * it. Below an object the first step is what that object points at, and the object itself is left out
   * because it is already on the chain this is an alternative stretch of.
   */
  private fun path(
    objectIndexes: IntArray,
    isBelowGroup: Boolean
  ): IndependentPath {
    val objectIds = objectIndexes.map { referrerIndex.objectIdAt(it) }
    val steps = objectIds.mapIndexedNotNull { index, objectId ->
      when {
        index > 0 -> stepTo(objectId, referrerId = objectIds[index - 1])
        // The GC root's own object, which no field of the heap dump points at.
        isBelowGroup -> step(objectId, reference = null)
        else -> null
      }
    }
    return IndependentPath(
      gcRootLabel = if (isBelowGroup) gcRootLabelOf(objectIds.first()) else null,
      // Kept from the object up: what holds it directly is what a reader is looking for, and the
      // plumbing between a GC root and an app's own objects rarely is. Cut before the statuses are
      // worked out, so that a status is never explained by an object the chain doesn't show.
      steps = steps.takeLast(MAX_PATH_STEPS).withLeakStatuses(),
      hiddenStepCount = (steps.size - MAX_PATH_STEPS).coerceAtLeast(0)
    )
  }

  /**
   * Which kind of GC root reaches [objectId], or that it's garbage nothing points at.
   *
   * Only the roots the tree followed, so that an object a local variable also happens to point at isn't
   * named after the local variable. See [TreeGcRootProvider].
   */
  private fun gcRootLabelOf(objectId: Long): String {
    val gcRoot = graph.gcRoots.firstOrNull {
      it.id == objectId && reachability.isHeldThrough(objectId, it.reachabilityStrength())
    }
    if (gcRoot != null) {
      return gcRootLabel(gcRoot)
    }
    val strength = strengthOf(objectId)
    if (strength != ReachabilityStrength.UNREACHABLE) {
      // Which is a chain calling an object garbage while the treemap draws it in the reachable half, and
      // the two can only disagree if the roots the tree walked from aren't the roots here.
      SharkLog.d {
        "The path to ${hexObjectId(objectId)} starts at no GC root this tree was built from, though the " +
          "object is reachable ($strength), so the path says uncollected garbage instead"
      }
    }
    return UNCOLLECTED_LABEL
  }

  /**
   * The steps of one path, each with a leak status: what the inspectors made of an object is only half of
   * what says whether it is leaking, and the other half is what they made of the objects above and below
   * it on this path. See [leakStatusesOf].
   */
  private fun List<InspectedStep>.withLeakStatuses(): List<PathStep> {
    val statuses = leakStatusesOf(map { it.inspected })
    return mapIndexed { index, inspected ->
      inspected.step.copy(
        leakStatus = statuses[index].status,
        leakStatusReason = statuses[index].reason
      )
    }
  }

  /** How [referrerId] points at [objectId], which takes reading the referrer's references again. */
  private fun stepTo(
    objectId: Long,
    referrerId: Long
  ): InspectedStep {
    val details = pathReferenceReader.read(graph.findObjectById(referrerId))
      .firstOrNull { it.valueObjectId == objectId }
      ?.lazyDetailsResolver
      ?.resolve()
    if (details == null) {
      // The walk found this step through the referrer index, which was built with this same reader, so a
      // step with no reference to name means the two disagree about the same pair of objects. Shows as a
      // step naming the object and not how it's held.
      SharkLog.d {
        "No reference from ${hexObjectId(referrerId)} to ${hexObjectId(objectId)}, though the path to it " +
          "was found through one"
      }
    }
    return step(
      objectId = objectId,
      reference = details?.let { resolved ->
        PathReference(
          name = resolved.name,
          // The class that declares the field rather than the referrer's own class, which is what tells a
          // field inherited from a base class apart from one the subclass added.
          ownerClassName = graph.findObjectByIdOrNull(resolved.locationClassObjectId)
            ?.let { (it as? HeapClass)?.simpleName }
            ?: label(referrerId),
          locationType = resolved.locationType,
          // Which is the whole of what the library leak matchers added to the reader do: they name the
          // references that are known to leak, and change nothing about which of them are followed.
          libraryLeak = resolved.matchedLibraryLeak?.let { matcher ->
            LibraryLeakPattern(pattern = matcher.pattern.toString(), description = matcher.description)
          }
        )
      }
    )
  }

  private fun step(
    objectId: Long,
    reference: PathReference?
  ): InspectedStep {
    val node = nodes[objectId]
    // Every step of a path is an object of the heap dump, unlike a node of the tree, which can stand for a
    // pile of them or for the dump as a whole.
    val heapObject = graph.findObjectById(objectId)
    val className = heapObject.className()
    val reporter = heapObject.inspect()
    return InspectedStep(
      step = PathStep(
        objectId = objectId,
        className = className,
        kind = heapObject.kind(),
        headline = heapObject.headline(),
        strength = strengthOf(objectId),
        // Zero for an object whose bytes are folded into another one, which is no node of the tree: a
        // string's characters are counted inside the string. See
        // [ReferenceStrengthReader.foldedObjectIdsOf].
        retainedSize = node?.retainedSize ?: 0L,
        retainedCount = node?.retainedCount ?: 0,
        inspectorLabels = reporter.labels.toList(),
        // Filled in by [withLeakStatuses] once the whole path is known, since that is what decides it.
        leakStatus = LeakStatus.UNKNOWN,
        leakStatusReason = null,
        reference = reference,
        isInspectable = objectId in nodes
      ),
      inspected = InspectedPathObject(
        simpleClassName = className.substringAfterLast('.'),
        leakingReasons = reporter.leakingReasons,
        notLeakingReasons = reporter.notLeakingReasons
      )
    )
  }

  /**
   * A search for the paths to one object, which walks the references backwards: from the object towards
   * whatever holds it, which is the direction a [ReferrerIndex] can answer in.
   *
   * Every path it hands out shares no object with the ones before it, because their middles are blocked
   * before the next walk. Greedy, so blocking can cost a path further on — see [IndependentPaths].
   *
   * Objects are object indexes throughout: this walks as much of a heap dump as it takes to find the
   * paths, which on an object the root dominates is everything above it.
   */
  private inner class PathSearch(private val targetIndex: Int) {
    /** The middles of the paths found so far, which the next walk goes around. */
    private val blocked = BooleanArray(referrerIndex.objectCount)

    /**
     * Which objects a path has already reached the target from.
     *
     * A source pointing straight at the target makes a path with no middle to block, so without this the
     * next walk would find that same path again. Only the last step is blocked and not the object itself,
     * because a source can hold the target several ways: what points straight at it is one of them, and
     * the chains round through other objects are the others.
     */
    private val usedLastStep = BooleanArray(referrerIndex.objectCount)

    /** Per walk: which object each one was reached from, one step closer to the target. */
    private val nextTowardsTarget = IntArray(referrerIndex.objectCount)
    private val queue = IntArray(referrerIndex.objectCount)

    /** The next path from a source down to the target, or null once there are no more to find. */
    fun findPath(isSource: (Int) -> Boolean): IntArray? {
      nextTowardsTarget.fill(NOT_REACHED)
      nextTowardsTarget[targetIndex] = targetIndex
      queue[0] = targetIndex
      var head = 0
      var tail = 1
      while (head < tail) {
        val current = queue[head++]
        if (current != targetIndex && isSource(current)) {
          return pathFrom(current)
        }
        val isLastStep = current == targetIndex
        referrerIndex.forEachReferrer(current) { referrer, _ ->
          // Not through the target: a walk that goes round through the object it started at would report
          // the object as holding itself.
          val isAvailable = !blocked[referrer] &&
            !(isLastStep && usedLastStep[referrer]) &&
            referrer != targetIndex
          if (nextTowardsTarget[referrer] == NOT_REACHED && isAvailable) {
            nextTowardsTarget[referrer] = current
            queue[tail++] = referrer
          }
        }
      }
      return null
    }

    /** The path from [sourceIndex] down to the target, blocking it for the next walk. */
    private fun pathFrom(sourceIndex: Int): IntArray {
      val path = mutableListOf(sourceIndex)
      var current = sourceIndex
      while (current != targetIndex) {
        current = nextTowardsTarget[current]
        path += current
      }
      // Everything but the two ends, which every path shares by definition, plus the step into the target.
      path.subList(1, path.size - 1).forEach { blocked[it] = true }
      usedLastStep[path[path.size - 2]] = true
      return path.toIntArray()
    }
  }

  /**
   * What's worth saying about an object before its fields, for the kinds this recognizes. Both cases
   * here are objects whose fields say nothing about their size: a bitmap keeps its pixels in native
   * memory, and a string's characters are folded into it by the size calculator.
   */
  private fun HeapObject.headline(): String? = when (this) {
    is HeapInstance -> when {
      instanceOf("java.lang.String") -> readAsJavaString()?.let { "\"$it\"" }
      instanceOf("android.graphics.Bitmap") -> bitmapHeadline()
      instanceOf("java.lang.Thread") -> readStringField("java.lang.Thread", "name")
        ?.let { "thread \"$it\"" }
      else -> null
    }
    is HeapObjectArray -> "${readRecord().elementIds.size} elements"
    is HeapPrimitiveArray -> "$recordSize bytes"
    is HeapClass -> null
  }

  private fun HeapInstance.bitmapHeadline(): String {
    val width = this[BITMAP_CLASS_NAME, "mWidth"]?.value?.asInt
    val height = this[BITMAP_CLASS_NAME, "mHeight"]?.value?.asInt
    val recycled = this[BITMAP_CLASS_NAME, "mRecycled"]?.value?.asBoolean == true
    return "$width × $height pixels" + if (recycled) ", recycled" else ""
  }

  private fun HeapInstance.readStringField(
    declaringClassName: String,
    fieldName: String
  ): String? = this[declaringClassName, fieldName]?.value?.readAsJavaString()

  /**
   * Every field of an object, with object valued ones inspectable so that the panel can walk the graph
   * the way the heap dump records it, rather than only the way the dominator tree summarizes it.
   *
   * An array's elements are fields here too, and an array can hold millions of them, so this counts
   * them all and reads only the first [MAX_FIELDS].
   */
  private fun HeapObject.fieldsOf(): FieldList = when (this) {
    is HeapInstance -> readFields().filterNot { it.isRuntimeInternal }.toList().let { fields ->
      FieldList(fields.take(MAX_FIELDS).map { it.asFieldValue(it.declaringClass.simpleName) }, fields.size)
    }
    is HeapClass -> readStaticFields().filterNot { it.isRuntimeInternal }.toList().let { fields ->
      FieldList(fields.take(MAX_FIELDS).map { it.asFieldValue(simpleName) }, fields.size)
    }
    is HeapObjectArray -> readRecord().elementIds.let { elementIds ->
      FieldList(
        elementIds.take(MAX_FIELDS).mapIndexed { index, elementId ->
          ObjectFieldValue(
            name = "[$index]",
            declaringClassName = null,
            value = if (elementId == NULL_REFERENCE) NULL_VALUE else render(elementId),
            inspectableObjectId = elementId.takeIf { it in nodes }
          )
        },
        elementIds.size
      )
    }
    is HeapPrimitiveArray -> readRecord().let { record ->
      FieldList(
        (0 until minOf(record.size, MAX_FIELDS)).map { index ->
          ObjectFieldValue(
            name = "[$index]",
            declaringClassName = null,
            value = record.elementAt(index),
            inspectableObjectId = null
          )
        },
        record.size
      )
    }
  }

  private fun PrimitiveArrayDumpRecord.elementAt(index: Int): String = when (this) {
    is BooleanArrayDump -> array[index].toString()
    is CharArrayDump -> "'${array[index]}'"
    is FloatArrayDump -> array[index].toString()
    is DoubleArrayDump -> array[index].toString()
    is ByteArrayDump -> array[index].toString()
    is ShortArrayDump -> array[index].toString()
    is IntArrayDump -> array[index].toString()
    is LongArrayDump -> array[index].toString()
  }

  private fun HeapField.asFieldValue(declaringClassName: String) = ObjectFieldValue(
    name = name,
    declaringClassName = declaringClassName,
    value = render(value),
    inspectableObjectId = value.asNonNullObjectId?.takeIf { it in nodes }
  )

  /** A reference reads as what it points at, so that `Thread.name` says `"main"` and not an address. */
  private fun render(value: HeapValue): String = when (val holder = value.holder) {
    is ReferenceHolder -> if (holder.isNull) NULL_VALUE else render(holder.value)
    is BooleanHolder -> holder.value.toString()
    is CharHolder -> "'${holder.value}'"
    is FloatHolder -> holder.value.toString()
    is DoubleHolder -> holder.value.toString()
    is ByteHolder -> holder.value.toString()
    is ShortHolder -> holder.value.toString()
    is IntHolder -> holder.value.toString()
    is LongHolder -> holder.value.toString()
  }

  private fun render(objectId: Long): String {
    val target = graph.findObjectByIdOrNull(objectId) ?: return UNKNOWN_VALUE
    return (target as? HeapInstance)?.readAsJavaString()?.let { "\"$it\"" } ?: label(objectId)
  }

  /**
   * Reads a label and a strength for every one of [cells]: everything the UI needs to draw a laid out
   * shape of this tree without touching the heap dump itself.
   *
   * What shape they are is no business of this, which is why there is one of these rather than one per
   * shape — a `TreemapCell`, a `RadialCell` and a `StackCell` are all a [CellSubject] with geometry, and
   * a name is read off the subject. Pairing a layout with this is [TreemapPresentation.of] and its two
   * siblings, so a fourth shape needs nothing here.
   */
  fun <C : LayoutCell<Long>> present(cells: List<C>): List<PresentedCell<C>> =
    cells.map { it.presented() }

  private fun <C : LayoutCell<Long>> C.presented(): PresentedCell<C> = when (val subject = subject) {
    is CellSubject.Node -> group(subject.node)?.let { group ->
      PresentedCell(
        cell = this,
        label = group.label(),
        content = CellContent.ObjectGroup(group.kind, group.strength, group.objectCount)
      )
    } ?: presentedObject(subject.node)
    is CellSubject.Group -> PresentedCell(
      cell = this,
      label = "${subject.nodeCount} smaller objects",
      content = CellContent.Leftover(strengthOf(subject.parent))
    )
    // An object's own bytes are that object, so this reads as the object and is where its name shows:
    // a subdivided rectangle has no room of its own to put a label in.
    is CellSubject.Own -> presentedObject(subject.node)
  }

  /**
   * One object as a cell, read once: a rectangle needs the object's name and whether it's a bitmap, and
   * a presentation of a production dump has a couple of thousand of them.
   */
  private fun <C : LayoutCell<Long>> C.presentedObject(node: Long): PresentedCell<C> {
    if (node == root) {
      return PresentedCell(
        cell = this,
        label = ROOT_LABEL,
        content = CellContent.Object(ReachabilityStrength.STRONG, isBitmap = false)
      )
    }
    val heapObject = graph.findObjectById(node)
    return PresentedCell(
      cell = this,
      label = heapObject.cellLabel(),
      content = CellContent.Object(strengthOf(node), bitmaps.isBitmap(heapObject))
    )
  }

  /**
   * The images of the bitmaps [objectIds], for however many of them anything has the pixels of, scaled
   * down to [maxDimension] where the pixels come raw. See [HeapBitmaps].
   */
  fun bitmapImages(
    objectIds: Collection<Long>,
    maxDimension: Int
  ): Map<Long, BitmapImage> {
    val images = LinkedHashMap<Long, BitmapImage>(objectIds.size)
    objectIds.forEach { objectId ->
      bitmaps.imageOf(objectId, maxDimension)?.let { images[objectId] = it }
    }
    return images
  }

  /** How many bitmaps this heap dump has, and how many of them can be drawn. */
  fun bitmapCounts(): BitmapCounts = bitmaps.counts()

  /**
   * Takes the pixels of the bitmaps of the live process this dump was written by, so that the bitmaps
   * of a dump that carries none can be drawn. See [HeapBitmaps.addNativePixels] and [DeviceHeapDumps].
   */
  fun addNativeBitmapPixels(pixels: NativeBitmapPixels): BitmapCounts =
    bitmaps.addNativePixels(pixels)

  /**
   * One step of a path, with what Shark's inspectors said about the object, before the path as a whole
   * turns that into a [LeakStatus]. See [withLeakStatuses].
   */
  private class InspectedStep(
    val step: PathStep,
    val inspected: InspectedPathObject
  )

  /** The fields of one object: what's shown, and how many there are in total. */
  private class FieldList(
    val shown: List<ObjectFieldValue>,
    val totalCount: Int
  )

  /** A cell standing for many objects rather than one. See [groupOrNull]. */
  private class NodeGroup(
    val kind: ObjectGroupKind,
    /** The node this group hangs off: the root, or the garbage pile for a class gathered inside it. */
    val parentNodeId: Long,
    val strength: ReachabilityStrength,
    /** What [children] answers for it. */
    val childIds: List<Long>,
    val retainedSize: Long,
    /** How many objects the cell stands for, which is more than [childIds] once they nest. */
    val objectCount: Int,
    val className: String? = null,
    val simpleClassName: String? = null
  )

  /** What [children] answers for the root, and every group of this tree by its id. */
  private class TopLevel(
    val ids: List<Long>,
    val groups: Map<Long, NodeGroup>,
    /** Which class group each grouped child of the root is drawn in. See [pathToOpen]. */
    val classGroupIdByObjectId: Map<Long, Long>
  )

  /**
   * One of the two halves the root's children split into — the reachable heap and the garbage — filled in
   * as [splitRootChildren] reads them. Only the garbage ends up a node; the other half is the root itself.
   *
   * Both what the children are and what they'd be gathered into, because which of the two a half ends up
   * handing out depends on how many there turn out to be, and reading them all again to find out would
   * cost as much as the first pass did.
   */
  private inner class TopLevelHalf(
    val strength: ReachabilityStrength,
    val objectCount: Int
  ) {
    val childIds = mutableListOf<Long>()

    /** The same children by the class they'd gather under. See [groupByClass]. */
    val idsByClassId = LinkedHashMap<Long, MutableList<Long>>()

    /** And the ones no class gathers, which is a class object in a dump without `java.lang.Class`. */
    val ungroupedIds = mutableListOf<Long>()

    var retainedSize = 0L
      private set

    fun add(
      heapObject: HeapObject,
      retainedSize: Long
    ) {
      childIds += heapObject.objectId
      this.retainedSize += retainedSize
      val classId = heapObject.groupingClassId()
      if (classId == null) {
        ungroupedIds += heapObject.objectId
      } else {
        idsByClassId.getOrPut(classId) { mutableListOf() } += heapObject.objectId
      }
    }
  }

  /**
   * Hands out the ids of the class groups of one tree, counting up from [FIRST_CLASS_GROUP_ID].
   *
   * A group is no object of the heap dump, so it needs an id of its own, out of the range no address of one
   * can land in — see [FIRST_PILE_ID]. Sequential rather than derived from the class, because the same class
   * can have a group on both sides of the tree.
   */
  private class GroupIds {
    private var nextId = FIRST_CLASS_GROUP_ID

    fun next(): Long = nextId++
  }

  companion object {
    /**
     * The object id of the virtual root, which every dominator tree of a heap dump has, so the UI can
     * root its navigation there before it has a tree to ask.
     */
    const val ROOT_OBJECT_ID = NULL_REFERENCE

    /** What the virtual root above the whole heap dump is called in the UI. */
    const val ROOT_LABEL = "Whole heap dump"

    /**
     * The first of the ids this tree hands out to a pile of objects, which count up from there.
     *
     * At the bottom of the range rather than just below zero, because a negative object id is a real
     * thing: an id is a heap address, and a 32 bit heap dump records it in 4 bytes, which shark widens by
     * sign — so every object above the 2 GB mark of such a dump has a negative id. See [isPileId].
     */
    private const val FIRST_PILE_ID = Long.MIN_VALUE

    /**
     * The uncollected garbage, one child of the root among the objects the GC roots reach. Absent from a
     * heap dump whose garbage was all collected before it was written.
     */
    const val UNREACHABLE_NODE_ID = FIRST_PILE_ID

    const val UNREACHABLE_LABEL = "Unreachable"

    /** The ids after it are the class groups. See [GroupIds]. */
    private const val FIRST_CLASS_GROUP_ID = FIRST_PILE_ID + 1

    /**
     * Between the count and the class name on a class group's cell, so that the label can't be read as
     * the name of one object.
     */
    const val CLASS_GROUP_LABEL_SEPARATOR = "×"

    /**
     * How many children a top level group has to have before they're gathered by class. Below this they
     * all fit on screen, and a level of classes to click through would be in the way.
     */
    const val MIN_CHILDREN_TO_GROUP_BY_CLASS = 200

    private const val JAVA_LANG_CLASS = "java.lang.Class"

    /**
     * Whether [nodeId] stands for a pile of objects rather than for one object of the heap dump: the two
     * halves of the tree, and the classes their children are gathered under.
     *
     * Which is a range check rather than a look at the sign, because an object id can be negative. The ids
     * of a heap dump are addresses, 8 bytes wide or 4 widened by sign, so they run from [Int.MIN_VALUE] up,
     * and this tree's own ids are below that. The root, [NULL_REFERENCE], is neither.
     */
    fun isPileId(nodeId: Long): Boolean = nodeId < SMALLEST_OBJECT_ID

    private const val SMALLEST_OBJECT_ID: Long = Int.MIN_VALUE.toLong()

    private const val BITMAP_CLASS_NAME = "android.graphics.Bitmap"
    private const val NULL_VALUE = "null"
    private const val UNKNOWN_VALUE = "object not in the heap dump"

    /** An array can hold millions of elements, and no panel is going to show them. */
    private const val MAX_FIELDS = 500

    /**
     * How many objects [listObjects] hands out. Past a few hundred rows the list stops being something
     * anyone reads and becomes something they filter, which is what the filter is for.
     */
    const val MAX_LISTED_OBJECTS = 500

    /**
     * How many leaking objects [findLeaks] walks up to the GC roots from, which is what it costs. A dump
     * with more than this has a handful of leaks with hundreds of instances each, and the largest of them
     * are the ones anyone reads.
     */
    private const val MAX_LEAKING_OBJECTS = 500

    /**
     * What a reference from a running method is called, since it has no name of its own. The same words
     * the chain pane uses, duplicated rather than shared: a leak named after a reference has to be named
     * the way the chain spells it, and that is a string, not a module's API.
     */
    private const val LOCAL_VARIABLE = "<local variable>"

    /** What the unreachable section says instead of a reason, since being gone is the whole of it. */
    private const val UNREACHABLE_LEAK_SUBTITLE =
      "Nothing reaches these any more: the next garbage collection would take them."

    /**
     * How many ways of holding an object [independentPathsBetween] spells out. Six chains is already more
     * than fits in a panel, and an object held from more places than that is held by a data structure
     * rather than by anything anyone would call an owner.
     */
    private const val MAX_INDEPENDENT_PATHS = 6

    /** How many steps of one path are shown, counted from the object up. */
    private const val MAX_PATH_STEPS = 15

    /**
     * And how many of a path from a GC root, counted the same way.
     *
     * Longer than an independent path's, because this one starts at a GC root rather than below the
     * dominator, and shorter than the chains a real heap dump has: the way from a thread down to a list
     * row is dozens of steps of framework plumbing, and each step shown is a read of the heap dump on the
     * way to answering what the pointer is on.
     */
    private const val MAX_ROOT_PATH_STEPS = 20

    /** No object index, so it stands for an object one walk of [PathSearch] hasn't reached. */
    private const val NOT_REACHED = -1

    /** What a path below the uncollected garbage starts at, since no GC root reaches it. */
    private const val UNCOLLECTED_LABEL = "Uncollected garbage"

    /**
     * ART gives every object a `shadow$_klass_` and a `shadow$_monitor_`: the class pointer and the
     * lock word. They're the runtime's business, and they're on every object in the list otherwise.
     */
    private val HeapField.isRuntimeInternal: Boolean get() = name.startsWith("shadow\$_")

    private fun gcRootLabel(gcRoot: GcRoot): String = "GC root: " + when (gcRoot) {
      is JniGlobal -> "JNI global reference"
      is JniLocal -> "JNI local reference"
      is JniMonitor -> "JNI monitor"
      is JavaFrame -> "local variable of a running method"
      is NativeStack -> "native stack"
      is StickyClass -> "loaded class"
      is ThreadBlock -> "thread block"
      is MonitorUsed -> "monitor in use"
      is ThreadObject -> "running thread"
      is ReferenceCleanup -> "reference cleanup"
      is VmInternal -> "runtime internal"
      is InternedString -> "interned string"
      is Finalizing -> "being finalized"
      is Debugger -> "held by the debugger"
      is Unreachable -> "unreachable"
      is Unknown -> "kind not recorded"
    }
  }
}

/** What the UI knows about one heap object. See [HeapDominatorTreemap.summarize]. */
data class HeapObjectSummary(
  val objectId: Long,
  /** Short name, as drawn on a rectangle. */
  val label: String,
  /** Fully qualified class name, or array type. */
  val className: String,
  /** Null for the virtual root above the heap dump, which is no object of it. */
  val kind: HeapObjectKind?,
  /**
   * What this kind of object is worth saying before anything else — a string's content, a bitmap's
   * dimensions — for the kinds the explorer recognizes, null for the rest.
   */
  val headline: String?,
  val strength: ReachabilityStrength,
  val shallowSize: Long,
  val retainedSize: Long,
  /** Number of objects retained, including this one. */
  val retainedCount: Int,
  /** Number of objects immediately dominated by this one, ie its children in the treemap. */
  val dominatedObjectCount: Int,
  /** What Shark's object inspectors have to say, e.g. that an activity is destroyed. */
  val inspectorLabels: List<String>,
  /** Its fields, or an array's elements, in the order the heap dump records them. */
  val fields: List<ObjectFieldValue>,
  /** How many more fields there are than [fields] holds, which only an array reaches. */
  val hiddenFieldCount: Int
)

/**
 * What the UI knows about a cell that stands for a pile of objects rather than for one. See
 * [HeapDominatorTreemap.groupOrNull].
 */
data class ObjectGroupSummary(
  /** What the tree knows this group by, e.g. to zoom into it. Not an object id. */
  val nodeId: Long,
  val kind: ObjectGroupKind,
  /** How firmly the objects in it are held, which they all are the same way. */
  val strength: ReachabilityStrength,
  /** Fully qualified class name, or array type, for [ObjectGroupKind.CLASS] only. */
  val className: String?,
  /** How many objects the cell stands for. */
  val objectCount: Int,
  /** Bytes retained by those objects together. */
  val retainedSize: Long
)

/** Which pile of objects a cell stands for. See [ObjectGroupSummary]. */
enum class ObjectGroupKind {

  /** Everything no GC root reaches: garbage that hadn't been collected when the heap dump was written. */
  UNREACHABLE,

  /** Every instance of one class that nothing in the heap dump owns on its own. */
  CLASS
}

/** One field of an object, or one element of an array. See [HeapObjectSummary.fields]. */
data class ObjectFieldValue(
  /** The field's name, or `[3]` for the fourth element of an array. */
  val name: String,
  /** Which class along the hierarchy declares the field, null for an array element. */
  val declaringClassName: String?,
  /** The value, rendered: a number, `null`, a string's content, or what class the object is. */
  val value: String,
  /** The object the field points at, when it's in the tree and can therefore be inspected. */
  val inspectableObjectId: Long?
)
