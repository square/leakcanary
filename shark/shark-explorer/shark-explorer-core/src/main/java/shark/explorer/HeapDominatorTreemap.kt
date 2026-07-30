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
 * puts above every GC root, so that the whole heap dump is one rectangle. Every object of the dump is in
 * here, whether a GC root reaches it or not — the uncollected garbage hangs under [UNREACHABLE_NODE_ID],
 * a sibling of [GC_ROOTS_NODE_ID], because it has no owner in the reachable heap and its bytes are still
 * bytes.
 *
 * The negative node ids stand for a pile of objects rather than for one: the two above, and one per
 * class the children of either are gathered under. They're allocated per tree, so they mean nothing to
 * another tree of the same heap dump.
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
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
   * The two halves of the heap dump, each with its own children gathered by class, computed on first
   * use: it's a pass over every child of the root, and the root of a production dump has six figures
   * worth of them.
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
  override fun weight(node: Long): Long = group(node)?.retainedSize
    ?: nodes.getValue(node).retainedSize

  override fun children(node: Long): List<Long> = when {
    node == root -> topLevel.ids
    else -> group(node)?.childIds ?: nodes.getValue(node).dominatedObjectIds
  }

  /** Whether [objectId] is a node of this tree, which every object of the heap dump is. */
  operator fun contains(objectId: Long): Boolean = if (isGroupId(objectId)) {
    objectId in topLevel.groups
  } else {
    objectId in nodes
  }

  /**
   * What a cell standing for many objects stands for, or null if [node] is one object.
   *
   * Two kinds of cell are a pile of objects. The first is the top of the tree: everything the GC roots
   * reach on one side, the uncollected garbage on the other, so that the two are read apart rather than
   * mixed into one list of rectangles.
   *
   * The second is a class. The root of a production heap dump has six figures worth of children — every
   * object that more than one thing holds ends up there — and no view can show them one by one. So the
   * children of each half are gathered by class, and a class stands in for its instances until you zoom
   * into it. Only there, and only past [MIN_CHILDREN_TO_GROUP_BY_CLASS] children: elsewhere in the tree a
   * node's children are what holds what, and replacing them with classes would throw that away; at the
   * top there is nothing to throw away, because being there means nothing owns you.
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
    if (isGroupId(node)) topLevel.groups[node] else null

  /**
   * Splits the root's children into the reachable heap and the uncollected garbage, and gathers each
   * side's children by class.
   */
  private fun splitRootChildren(): TopLevel {
    val reachable = TopLevelHalf(
      nodeId = GC_ROOTS_NODE_ID,
      kind = ObjectGroupKind.GC_ROOTS,
      strength = ReachabilityStrength.STRONG,
      objectCount = reachability.sizes.reachableObjectCount
    )
    val unreachable = TopLevelHalf(
      nodeId = UNREACHABLE_NODE_ID,
      kind = ObjectGroupKind.UNREACHABLE,
      strength = ReachabilityStrength.UNREACHABLE,
      objectCount = reachability.sizes.unreachableObjectCount
    )
    // One pass, one read of each child: a production dump has six figures worth of them, and looking one
    // up twice — once for its strength and once for its class — was seconds of the wait to first paint.
    nodes.getValue(root).dominatedObjectIds.forEach { objectId ->
      val heapObject = graph.findObjectById(objectId)
      val half = if (reachability.strengthOf(heapObject) == ReachabilityStrength.UNREACHABLE) {
        unreachable
      } else {
        reachable
      }
      half.add(heapObject, nodes.getValue(objectId).retainedSize)
    }
    val groups = LinkedHashMap<Long, NodeGroup>()
    val ids = mutableListOf<Long>()
    val groupIds = GroupIds()
    // The garbage second, and only when there is some: a dump with none shouldn't grow a rectangle that
    // says so.
    listOf(reachable, unreachable).forEach { half ->
      if (half.childIds.isNotEmpty()) {
        ids += half.nodeId
        groups[half.nodeId] = topLevelGroup(half, groups, groupIds)
      }
    }
    val classGroupIdByObjectId = mutableMapOf<Long, Long>()
    groups.forEach { (groupId, group) ->
      if (group.kind == ObjectGroupKind.CLASS) {
        group.childIds.forEach { classGroupIdByObjectId[it] = groupId }
      }
    }
    return TopLevel(ids = ids, groups = groups, classGroupIdByObjectId = classGroupIdByObjectId)
  }

  /**
   * One half of the heap dump as a group, with the class groups its children were gathered into added
   * to [groups].
   */
  private fun topLevelGroup(
    half: TopLevelHalf,
    groups: MutableMap<Long, NodeGroup>,
    groupIds: GroupIds
  ): NodeGroup {
    val grouped = if (half.childIds.size <= MIN_CHILDREN_TO_GROUP_BY_CLASS) {
      half.childIds
    } else {
      groupByClass(half, groups, groupIds)
    }
    return NodeGroup(
      kind = half.kind,
      parentNodeId = root,
      strength = half.strength,
      // Heaviest first, like the dominated ids a node hands out, so that these children stay ordered the
      // way the rest of the tree's are. Not through [weight], which would ask for the groups being built.
      childIds = grouped.sortedByDescending { groups[it]?.retainedSize ?: nodes.getValue(it).retainedSize },
      retainedSize = half.retainedSize,
      objectCount = half.objectCount
    )
  }

  /**
   * What one half's children become once gathered by class: a group id per class with more than one
   * instance in it, and the other ids as they were. Adds a group per class to [groups].
   */
  private fun groupByClass(
    half: TopLevelHalf,
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
        val heapClass = graph.findObjectById(classId).asClass!!
        val groupId = groupIds.next()
        groups[groupId] = NodeGroup(
          kind = ObjectGroupKind.CLASS,
          parentNodeId = half.nodeId,
          className = heapClass.name,
          simpleClassName = heapClass.simpleName,
          strength = half.strength,
          childIds = objectIds,
          retainedSize = objectIds.sumOf { nodes.getValue(it).retainedSize },
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
   * The id of a class by name, looked up once per name: [HeapGraph.findClassByName] performs two linear
   * scans over every string of the heap dump, and asking it per object of a production dump — once for
   * every class object, once for every `byte[]` — took the best part of a minute.
   */
  private fun classIdOf(className: String): Long? =
    classIdByName.getOrPut(className) { graph.findClassByName(className)?.objectId }

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
      return when (group.kind) {
        ObjectGroupKind.GC_ROOTS -> GC_ROOTS_LABEL
        ObjectGroupKind.UNREACHABLE -> UNREACHABLE_LABEL
        // "42 × Bitmap" rather than "Bitmap": a count and a multiplication sign say this cell is a pile
        // of objects and not one of them, on a rectangle with room for nothing else.
        ObjectGroupKind.CLASS ->
          "${group.objectCount} $CLASS_GROUP_LABEL_SEPARATOR ${group.simpleClassName}"
      }
    }
    return when (val heapObject = graph.findObjectById(node)) {
      is HeapClass -> "class ${heapObject.simpleName}"
      is HeapInstance -> heapObject.instanceClassSimpleName
      is HeapObjectArray -> heapObject.arrayClassSimpleName
      is HeapPrimitiveArray -> heapObject.arrayClassName
    }
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
    val node = nodes.getValue(objectId)
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
      inspectorLabels = heapObject?.inspectorLabels() ?: emptyList(),
      fields = fields.shown,
      hiddenFieldCount = fields.totalCount - fields.shown.size
    )
  }

  /** What Shark's inspectors have to say about an object, e.g. that an activity is destroyed. */
  private fun HeapObject.inspectorLabels(): List<String> {
    val reporter = ObjectReporter(this)
    AndroidObjectInspectors.appDefaults.forEach { it.inspect(reporter) }
    return reporter.labels.toList()
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
    // Nothing owns it, so which half of the tree it sits in is what stands in for a dominator: the object
    // is drawn there, and that half is what would have to go for it to be freed.
    val halfId = if (strengthOf(objectId) == ReachabilityStrength.UNREACHABLE) {
      UNREACHABLE_NODE_ID
    } else {
      GC_ROOTS_NODE_ID
    }
    return ObjectDominator(
      nodeId = halfId,
      label = label(halfId),
      retainedSize = weight(halfId),
      kind = if (halfId == UNREACHABLE_NODE_ID) {
        DominatorKind.UNCOLLECTED_GARBAGE
      } else {
        DominatorKind.ALL_GC_ROOTS
      }
    )
  }

  /**
   * Every way [objectId] is held below its dominator, spelled out field by field. See
   * [IndependentPaths] for what "independent" means and what this search does and doesn't guarantee.
   *
   * A bitmap under the root turns out to be held by the view showing it on one path and by an image cache
   * on another: the view is the answer anyone is after, and the cache is why the dominator tree had
   * nowhere to put its bytes but the whole heap.
   *
   * At most [MAX_INDEPENDENT_PATHS] of them, each at most [MAX_PATH_STEPS] steps long. The first call for
   * a heap dump builds a [ReferrerIndex], which reads the whole dump; the paths themselves are then walks
   * in memory, so the wait is once per dump rather than once per object.
   */
  fun independentPathsTo(objectId: Long): IndependentPaths {
    if (objectId == root || objectId !in nodes) {
      return IndependentPaths.NONE
    }
    val dominator = dominatorOf(objectId) ?: return IndependentPaths.NONE
    val targetIndex = referrerIndex.indexOf(objectId)
    if (targetIndex == ReferrerIndex.NOT_AN_OBJECT) {
      return IndependentPaths.NONE
    }
    val isBelowGroup = dominator.kind != DominatorKind.OBJECT
    val isSource: (Int) -> Boolean = if (isBelowGroup) {
      // Below a group, a path starts where the tree's own walk did: at a GC root, or at a piece of
      // garbage nothing else points at.
      ({ index -> index in treeRootIndexes })
    } else {
      val dominatorIndex = referrerIndex.indexOf(dominator.nodeId)
      ({ index -> index == dominatorIndex })
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
    return IndependentPaths(paths = paths, hasMore = paths.size == MAX_INDEPENDENT_PATHS)
  }

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
      // A class group hangs off the half its instances are in; a half hangs off the root, which the path
      // starts at anyway.
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
    return listOf(root, halfContaining(topLevelObjectId)) +
      listOfNotNull(topLevel.classGroupIdByObjectId[topLevelObjectId]) +
      dominators
  }

  private fun halfContaining(objectId: Long): Long =
    if (strengthOf(objectId) == ReachabilityStrength.UNREACHABLE) {
      UNREACHABLE_NODE_ID
    } else {
      GC_ROOTS_NODE_ID
    }

  /**
   * One found path as the UI shows it: from the step below the dominator down to the object.
   *
   * Below a group the first step is the GC rooted object itself, named by the kind of root that reaches
   * it. Below an object the first step is what the dominator points at, and the dominator is left out
   * because the panel shows it above the paths.
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
      // plumbing between a GC root and an app's own objects rarely is.
      steps = steps.takeLast(MAX_PATH_STEPS),
      hiddenStepCount = (steps.size - MAX_PATH_STEPS).coerceAtLeast(0)
    )
  }

  /**
   * Which kind of GC root reaches [objectId], or that it's garbage nothing points at.
   *
   * Only the roots the tree followed, so that an object a local variable also happens to point at isn't
   * named after the local variable. See [TreeGcRootProvider].
   */
  private fun gcRootLabelOf(objectId: Long): String = graph.gcRoots
    .firstOrNull { it.id == objectId && reachability.isHeldThrough(objectId, it.reachabilityStrength()) }
    ?.let { gcRootLabel(it) }
    ?: UNCOLLECTED_LABEL

  /** How [referrerId] points at [objectId], which takes reading the referrer's references again. */
  private fun stepTo(
    objectId: Long,
    referrerId: Long
  ): PathStep {
    val details = pathReferenceReader.read(graph.findObjectById(referrerId))
      .firstOrNull { it.valueObjectId == objectId }
      ?.lazyDetailsResolver
      ?.resolve()
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
          locationType = resolved.locationType
        )
      }
    )
  }

  private fun step(
    objectId: Long,
    reference: PathReference?
  ): PathStep {
    val node = nodes[objectId]
    // Every step of a path is an object of the heap dump, unlike a node of the tree, which can stand for a
    // pile of them or for the dump as a whole.
    val heapObject = graph.findObjectById(objectId)
    return PathStep(
      objectId = objectId,
      className = heapObject.className(),
      kind = heapObject.kind(),
      headline = heapObject.headline(),
      strength = strengthOf(objectId),
      // Zero for an object whose bytes are folded into another one, which is no node of the tree: a
      // string's characters are counted inside the string. See [ReferenceStrengthReader.foldedObjectIdsOf].
      retainedSize = node?.retainedSize ?: 0L,
      retainedCount = node?.retainedCount ?: 0,
      inspectorLabels = heapObject.inspectorLabels(),
      reference = reference,
      isInspectable = objectId in nodes
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
        referrerIndex.forEachReferrer(current) { referrer ->
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
   * Lays this tree out into [viewport] rooted at [root], and reads a label and a strength for every
   * rectangle: everything the UI needs to draw a treemap without touching the heap dump itself.
   */
  fun present(
    layout: TreemapLayout<Long>,
    viewport: TreemapRect,
    root: Long = this.root
  ): TreemapPresentation {
    val result = layout.layout(this, viewport, root)
    return TreemapPresentation(layout = result, cells = result.cells.map { it.presented() })
  }

  /** The same, laid out as rings around a centre rather than as rectangles. */
  fun presentRadial(
    layout: RadialLayout<Long>,
    viewport: TreemapRect,
    root: Long = this.root
  ): RadialPresentation {
    val result = layout.layout(this, viewport, root)
    return RadialPresentation(layout = result, cells = result.cells.map { it.presented() })
  }

  private fun <C : LayoutCell<Long>> C.presented(): PresentedCell<C> = when (val subject = subject) {
    is CellSubject.Node -> PresentedCell(
      cell = this,
      label = label(subject.node),
      content = group(subject.node)?.let { group ->
        CellContent.ObjectGroup(group.kind, group.strength, group.objectCount)
      } ?: CellContent.Object(strengthOf(subject.node))
    )
    is CellSubject.Group -> PresentedCell(
      cell = this,
      label = "${subject.nodeCount} smaller objects",
      content = CellContent.Leftover(strengthOf(subject.parent))
    )
  }

  /** The fields of one object: what's shown, and how many there are in total. */
  private class FieldList(
    val shown: List<ObjectFieldValue>,
    val totalCount: Int
  )

  /** A cell standing for many objects rather than one. See [groupOrNull]. */
  private class NodeGroup(
    val kind: ObjectGroupKind,
    /** The node this group hangs off: the root for a half of the tree, a half for a class. */
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
   * One of the two halves the root's children split into, filled in as [splitRootChildren] reads them.
   *
   * Both what the children are and what they'd be gathered into, because which of the two a half ends up
   * handing out depends on how many there turn out to be, and reading them all again to find out would
   * cost as much as the first pass did.
   */
  private inner class TopLevelHalf(
    val nodeId: Long,
    val kind: ObjectGroupKind,
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
   * Hands out the ids of the class groups of one tree, counting down from [FIRST_CLASS_GROUP_ID].
   *
   * A group is no object of the heap dump, so it needs an id of its own, and the negative range is free:
   * object ids are heap addresses. Sequential rather than derived from the class, because the same class
   * can have a group on both sides of the tree.
   */
  private class GroupIds {
    private var nextId = FIRST_CLASS_GROUP_ID

    fun next(): Long = nextId--
  }

  companion object {
    /**
     * The object id of the virtual root, which every dominator tree of a heap dump has, so the UI can
     * root its navigation there before it has a tree to ask.
     */
    const val ROOT_OBJECT_ID = NULL_REFERENCE

    /** What the virtual root above the whole heap dump is called in the UI. */
    const val ROOT_LABEL = "Whole heap dump"

    /** Everything the GC roots reach, one of the root's two children. */
    const val GC_ROOTS_NODE_ID = -1L

    const val GC_ROOTS_LABEL = "All GC roots"

    /**
     * The uncollected garbage, the root's other child. Absent from a heap dump whose garbage was all
     * collected before it was written.
     */
    const val UNREACHABLE_NODE_ID = -2L

    const val UNREACHABLE_LABEL = "Unreachable"

    /** The rest of the negative ids are the class groups. See [GroupIds]. */
    private const val FIRST_CLASS_GROUP_ID = -3L

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
     * Every object id in a heap dump is positive, so the sign is what tells a group of objects from one
     * object. The root is [NULL_REFERENCE], neither.
     */
    private fun isGroupId(node: Long) = node < 0L

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
     * How many ways of holding an object [independentPathsTo] spells out. Six chains is already more than
     * fits in a panel, and an object held from more places than that is held by a data structure rather
     * than by anything anyone would call an owner.
     */
    private const val MAX_INDEPENDENT_PATHS = 6

    /** How many steps of one path are shown, counted from the object up. */
    private const val MAX_PATH_STEPS = 15

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

  /** Everything the GC roots reach, however weakly. */
  GC_ROOTS,

  /** Everything they don't: garbage that hadn't been collected when the heap dump was written. */
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
