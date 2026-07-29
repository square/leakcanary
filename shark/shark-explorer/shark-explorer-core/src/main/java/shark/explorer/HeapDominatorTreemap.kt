package shark.explorer

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
import shark.HeapField
import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HeapValue
import shark.MatchingGcRootProvider
import shark.PrioritizingShortestPathFinder
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
import shark.ReferenceLocationType
import shark.ReferenceReader
import shark.AndroidObjectInspectors
import shark.internal.ReferencePathNode
import shark.internal.ReferencePathNode.ChildNode
import shark.internal.ReferencePathNode.RootNode
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
    WeakeningAwareReferenceReader(strengthReader, reachability)
  }

  /**
   * The objects the root dominates, which is to say the ones nothing owns: whatever holds them does so
   * on paths that meet only at the root. A set because [holdingPathsTo] asks this of every object it
   * walks past, and the root of a production dump has six figures worth of children.
   */
  private val rootDominatedIds: Set<Long> by lazy {
    nodes.getValue(root).dominatedObjectIds.toSet()
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
    return TopLevel(ids = ids, groups = groups)
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
      className = when (heapObject) {
        null -> ROOT_LABEL
        is HeapClass -> heapObject.name
        is HeapInstance -> heapObject.instanceClassName
        is HeapObjectArray -> heapObject.arrayClassName
        is HeapPrimitiveArray -> heapObject.arrayClassName
      },
      headline = heapObject?.headline(),
      strength = strengthOf(objectId),
      shallowSize = node.shallowSize,
      retainedSize = node.retainedSize,
      retainedCount = node.retainedCount,
      dominatedObjectCount = node.dominatedObjectIds.size,
      inspectorLabels = if (heapObject == null) {
        emptyList()
      } else {
        val reporter = ObjectReporter(heapObject)
        AndroidObjectInspectors.appDefaults.forEach { it.inspect(reporter) }
        reporter.labels.toList()
      },
      fields = fields.shown,
      hiddenFieldCount = fields.totalCount - fields.shown.size
    )
  }

  /**
   * What holds on to [objectId]: the fields pointing at it, and the GC roots if any point straight at
   * it.
   *
   * This is how an object ends up dominated by nothing but the virtual root: two referrers on paths
   * that only meet at the root mean neither of them alone would free it, so its bytes are attributed to
   * the whole heap rather than to either owner. The dominator tree can't say that on its own, and it's
   * the first thing you want to know about a big rectangle sitting flat under the root.
   *
   * Costs a pass over every object in the heap dump — around a second per 100 MB — because a heap dump
   * only records references in the direction they point. Hence a call of its own rather than part of
   * [summarize]: the panel fills the rest in straight away and this a moment later.
   *
   * Counts every referrer but keeps only the first [MAX_REFERRERS]: something like `Boolean.TRUE` is
   * held from tens of thousands of places, and the count is the useful part of that anyway.
   */
  fun referrersOf(objectId: Long): ObjectReferrers {
    var totalCount = 0
    var holdingCount = 0
    val referrers = mutableListOf<Referrer>()
    fun add(
      isHolding: Boolean,
      referrer: () -> Referrer
    ) {
      totalCount++
      if (isHolding) {
        holdingCount++
      }
      if (referrers.size < MAX_REFERRERS) {
        referrers += referrer()
      }
    }
    // Whether the tree follows a reference that doesn't retain depends on the object it points at, not
    // on the reference: it's followed only when it's the strongest thing reaching it. So this is one
    // answer for all of them. See [WeakeningAwareReferenceReader].
    val followsWeakening = strengthOf(objectId) != ReachabilityStrength.STRONG
    graph.gcRoots
      .filter { it.id == objectId }
      .forEach { gcRoot ->
        add(isHolding = true) {
          Referrer(
            label = gcRootLabel(gcRoot),
            fieldName = null,
            inspectableObjectId = null
          )
        }
      }
    graph.objects.forEach { heapObject ->
      strengthReader.retainingReferencesOf(heapObject)
        .filter { it.valueObjectId == objectId }
        .forEach { reference ->
          add(isHolding = true) { referrer(heapObject, reference.lazyDetailsResolver.resolve().name) }
        }
      strengthReader.weakeningReferencesOf(heapObject)
        .filter { it.valueObjectId == objectId }
        .forEach { weakening ->
          add(isHolding = followsWeakening) {
            referrer(heapObject, weakening.fieldName, weakening.strength, followsWeakening)
          }
        }
    }
    return ObjectReferrers(
      isDominatedByRoot = objectId != root && objectId in rootDominatedIds,
      referrers = referrers,
      holdingReferrerCount = holdingCount,
      hiddenReferrerCount = totalCount - referrers.size
    )
  }

  private fun referrer(
    heapObject: HeapObject,
    fieldName: String,
    weakeningStrength: ReachabilityStrength? = null,
    isFollowed: Boolean = true
  ) = Referrer(
    label = label(heapObject.objectId),
    fieldName = fieldName,
    inspectableObjectId = heapObject.objectId.takeIf { it in nodes },
    weakeningStrength = weakeningStrength,
    isFollowed = isFollowed
  )

  /**
   * Every way [objectId] is held, spelled out from a GC root down to it, field by field.
   *
   * [referrersOf] says which objects hold this one; this says what holds *those*, all the way up, which
   * is what answers "what is keeping this in memory". A bitmap under the root turns out to be held by
   * the view showing it on two of its paths and by an image cache on the third: the view is the answer
   * anyone is after, and the cache is why the dominator tree couldn't give it.
   *
   * At most [MAX_HOLDING_PATHS] of them, and it stops forking after [MAX_HOLDER_LEVELS] levels, saying
   * how many holders it didn't follow: something like a boxed `true` is held from tens of thousands of
   * places, and no panel is going to show them.
   *
   * Costs a pass over the heap dump per level it forks at, plus a walk from the GC roots — a few seconds
   * on a large dump — so it belongs off the UI thread and behind its own placeholder, like [referrersOf].
   */
  fun holdingPathsTo(objectId: Long): HoldingPaths {
    val isUnreachable = objectId != root &&
      objectId in nodes &&
      strengthOf(objectId) == ReachabilityStrength.UNREACHABLE
    // No path from a GC root reaches uncollected garbage, by definition, so there is nothing to walk:
    // whatever points at it is garbage as well, and the tree shows that as the rectangle around it.
    if (objectId == root || objectId !in nodes || isUnreachable) {
      return HoldingPaths(
        paths = emptyList(),
        commonHolderObjectId = null,
        commonHolderLabel = null,
        isDominatedByRoot = false,
        isUnreachable = isUnreachable,
        hiddenPathCount = 0
      )
    }
    val tails = tailsToWalkUpFrom(objectId)
    val paths = shortestPathsTo(tails.tailByTipId.keys)
      .map { (tipId, upperSteps) ->
        val tail = tails.tailByTipId.getValue(tipId)
        upperSteps.gcRootLabel to upperSteps.steps + tail.map { stepTo(it.objectId, it.referrerId) }
      }
      // A path that already went through the object isn't a way of holding it: a view's own helpers
      // point back at the view, and following one of those leads back to where it started.
      .filterNot { (_, steps) -> steps.dropLast(1).any { it.objectId == objectId } }
      .map { (gcRootLabel, steps) ->
        HoldingPath(
          gcRootLabel = gcRootLabel,
          // Kept from the object up, because what holds it directly is what a reader is looking for and
          // the framework plumbing between a GC root and the app's own objects rarely is.
          steps = steps.takeLast(MAX_PATH_STEPS),
          hiddenStepCount = (steps.size - MAX_PATH_STEPS).coerceAtLeast(0)
        )
      }
      .sortedBy { it.steps.size }
    return withPathCounts(
      objectId = objectId,
      paths = paths,
      // A tip with no path of its own was on the way to another tip, so its path is already shown.
      hiddenPathCount = tails.notFollowedCount
    )
  }

  /**
   * The objects to ask a path from a GC root for, each with the steps from it back down to the object
   * asked about.
   *
   * What forks comes out of what the dominator tree already knows: every path to an object with a
   * dominator goes through that dominator, so one chain says everything there is to say about how it's
   * held, however many references point at it. Only an object the root dominates is held in ways that
   * differ, and only those fork — which is what keeps this section and the treemap telling the same
   * story, and what makes an owned object cost the walk from the GC roots and nothing else.
   */
  private fun tailsToWalkUpFrom(objectId: Long): Tails {
    val tailByTipId = LinkedHashMap<Long, List<TailStep>>()
    var notFollowedCount = 0
    var frontier = mapOf(objectId to emptyList<TailStep>())
    repeat(MAX_HOLDER_LEVELS) {
      val forking = frontier.filterKeys { it in rootDominatedIds }
      tailByTipId += frontier - forking.keys
      if (forking.isEmpty()) {
        return Tails(tailByTipId, notFollowedCount)
      }
      val referrerIds = referrerIdsOf(forking.keys)
      val forked = LinkedHashMap<Long, List<TailStep>>()
      forking.forEach { (heldId, tail) ->
        val referrers = referrerIds[heldId].orEmpty()
        if (referrers.isEmpty()) {
          // Nothing in the heap dump points at it, so a GC root does, and the path finder starts there.
          tailByTipId[heldId] = tail
          return@forEach
        }
        // At least one per fork, however many forks there already are: a path that stops short of the
        // object would be a path to something else.
        val room = (MAX_HOLDING_PATHS - tailByTipId.size - forked.size).coerceAtLeast(1)
        referrers.take(room).forEach { referrerId ->
          forked.putIfAbsent(referrerId, listOf(TailStep(heldId, referrerId)) + tail)
        }
        notFollowedCount += (referrers.size - room).coerceAtLeast(0)
      }
      frontier = forked
    }
    tailByTipId += frontier
    return Tails(tailByTipId, notFollowedCount)
  }

  /** Which objects hold each of [targets], in one pass over the heap dump. */
  private fun referrerIdsOf(targets: Set<Long>): Map<Long, List<Long>> {
    val referrerIds = mutableMapOf<Long, MutableList<Long>>()
    graph.objects.forEach { heapObject ->
      pathReferenceReader.read(heapObject).forEach { reference ->
        if (reference.valueObjectId in targets) {
          val referrers = referrerIds.getOrPut(reference.valueObjectId) { mutableListOf() }
          // Two fields of the same object pointing at it is one holder, not two.
          if (referrers.lastOrNull() != heapObject.objectId) {
            referrers += heapObject.objectId
          }
        }
      }
    }
    return referrerIds
  }

  /**
   * The shortest path from a GC root to each of [targets], as the steps down to it.
   *
   * [PrioritizingShortestPathFinder] treats its targets as leaves, so a target that holds another one
   * hides it: asking for a tile and the view it holds reports the tile only. Hence a second walk for
   * whatever went missing, without the targets that swallowed it — two rounds, because a third target
   * nested under those two would be one holder explaining another explaining another, which the panel
   * couldn't show as separate ways of holding anything anyway.
   */
  private fun shortestPathsTo(targets: Set<Long>): Map<Long, UpperSteps> {
    val found = mutableMapOf<Long, UpperSteps>()
    var remaining = targets
    repeat(MAX_PATH_FINDING_ROUNDS) {
      if (remaining.isEmpty()) {
        return found
      }
      val pathFinder = PrioritizingShortestPathFinder.Factory(
        listener = {},
        referenceReaderFactory = object : ReferenceReader.Factory<HeapObject> {
          override fun createFor(heapGraph: HeapGraph) = pathReferenceReader
        },
        gcRootProvider = MatchingGcRootProvider(emptyList())
      ).createFor(graph)
      pathFinder.findShortestPathsFromGcRoots(remaining)
        .pathsToLeakingObjects
        .forEach { leaf -> found[leaf.objectId] = upperSteps(leaf) }
      remaining = remaining - found.keys
    }
    return found
  }

  /** One reported path, from its GC root down to the object it was asked for. */
  private fun upperSteps(leaf: ReferencePathNode): UpperSteps {
    val pathNodes = ArrayDeque<ReferencePathNode>()
    var node = leaf
    while (node is ChildNode) {
      pathNodes.addFirst(node)
      node = node.parent
    }
    val rootNode = node as RootNode
    pathNodes.addFirst(rootNode)
    return UpperSteps(
      gcRootLabel = gcRootLabel(rootNode.gcRoot),
      steps = pathNodes.map { pathNode ->
        step(
          objectId = pathNode.objectId,
          referenceName = (pathNode as? ChildNode)?.lazyDetailsResolver?.resolve()?.let { details ->
            referenceName(details.name, details.locationType)
          }
        )
      }
    )
  }

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
      referenceName = details?.let { referenceName(it.name, it.locationType) }
    )
  }

  private fun step(
    objectId: Long,
    referenceName: String?
  ) = PathStep(
    objectId = objectId,
    label = label(objectId),
    referenceName = referenceName,
    // Filled in once every path is known: a step is only worth pointing out if others go through it.
    pathCount = 1,
    isInspectable = objectId in nodes
  )

  /** Counts how many paths go through each step, and names the one every path goes through. */
  private fun withPathCounts(
    objectId: Long,
    paths: List<HoldingPath>,
    hiddenPathCount: Int
  ): HoldingPaths {
    val pathCountByObjectId = mutableMapOf<Long, Int>()
    paths.forEach { path ->
      path.steps.map { it.objectId }.distinct().forEach { stepId ->
        pathCountByObjectId[stepId] = (pathCountByObjectId[stepId] ?: 0) + 1
      }
    }
    val counted = paths.map { path ->
      path.copy(
        steps = path.steps.map { step ->
          step.copy(pathCount = pathCountByObjectId.getValue(step.objectId))
        }
      )
    }
    // The object every path goes through is the object's dominator, and the tree already knows which one
    // that is. Naming the deepest step the shown paths happen to share instead would be a guess: each
    // path is only the shortest way to one of the holders, so sharing a step says nothing about the ways
    // round it that weren't walked. Searched from the object up along the shortest path, which the
    // dominator is on if it's shown at all, being on all of them.
    val commonHolder = counted.firstOrNull()
      ?.steps
      ?.lastOrNull { it.objectId != objectId && dominates(it.objectId, objectId) }
    return HoldingPaths(
      paths = counted,
      commonHolderObjectId = commonHolder?.objectId,
      commonHolderLabel = commonHolder?.label,
      isDominatedByRoot = objectId in rootDominatedIds,
      isUnreachable = false,
      hiddenPathCount = hiddenPathCount
    )
  }

  /**
   * Whether [holderId] is what the tree attributes [objectId]'s bytes to, which is to say whether every
   * path to it goes through [holderId].
   *
   * A scan of one node's children rather than a reverse index of the whole tree: this is asked of the
   * handful of objects on one path, and the tree has a node per reachable object.
   */
  private fun dominates(
    holderId: Long,
    objectId: Long
  ): Boolean = nodes[holderId]?.dominatedObjectIds?.contains(objectId) == true

  /** An array element reads as `[3]`, so that a path can't be read as a field called `3`. */
  private fun referenceName(
    name: String,
    locationType: ReferenceLocationType
  ): String = if (locationType == ReferenceLocationType.ARRAY_ENTRY) "[$name]" else name

  /** One step of a path below the object a path was asked for. See [tailsToWalkUpFrom]. */
  private class TailStep(
    val objectId: Long,
    val referrerId: Long
  )

  /** Where [tailsToWalkUpFrom] stopped walking up, and what it didn't follow. */
  private class Tails(
    val tailByTipId: Map<Long, List<TailStep>>,
    /** How many holders were left unfollowed because there were more of them than paths to show. */
    val notFollowedCount: Int
  )

  /** A path from a GC root down to the object it was asked for. See [shortestPathsTo]. */
  private class UpperSteps(
    val gcRootLabel: String,
    val steps: List<PathStep>
  )

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
    val groups: Map<Long, NodeGroup>
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

    /** Same, for the objects holding a widely shared one. */
    private const val MAX_REFERRERS = 100

    /**
     * How many ways of holding an object [holdingPathsTo] spells out. Six chains is already more than
     * fits in a panel, and an object held from more places than that is held by a data structure rather
     than by anything anyone would call an owner.
     */
    private const val MAX_HOLDING_PATHS = 6

    /**
     * How many times the walk up from an object forks before it stops. Each fork is a pass over the heap
     * dump, and two is what it takes to get past the wrapper an object is usually held through — a
     * cache entry, a result object — to the holders that differ from each other.
     */
    private const val MAX_HOLDER_LEVELS = 2

    /** How many steps of one path are shown, counted from the object up. */
    private const val MAX_PATH_STEPS = 15

    /** See [shortestPathsTo]: one walk from the GC roots, plus one for whatever a target hid. */
    private const val MAX_PATH_FINDING_ROUNDS = 2

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

/** What holds on to an object. See [HeapDominatorTreemap.referrersOf]. */
data class ObjectReferrers(
  /**
   * Whether nothing but the virtual root dominates the object, which is what makes its referrers worth
   * showing: with more than one of them on paths that meet only at the root, no single owner would free
   * it, so the dominator tree attributes its bytes to the whole heap.
   */
  val isDominatedByRoot: Boolean,
  val referrers: List<Referrer>,
  /**
   * How many of the referrers keep the object in memory, which is fewer than [referrerCount] whenever
   * one of them holds it without retaining it — a `Cleaner` pointing at a bitmap, an entry of a cache.
   * Those are worth listing, but they aren't why the object is still here, and no path goes through
   * them.
   */
  val holdingReferrerCount: Int,
  /** How many referrers there are beyond the ones in [referrers]. */
  val hiddenReferrerCount: Int
) {
  /** How many references point at this object, including the ones [referrers] left out. */
  val referrerCount: Int get() = referrers.size + hiddenReferrerCount
}

/** One reference pointing at an object. See [ObjectReferrers]. */
data class Referrer(
  /** The referring object, or which kind of GC root this is. */
  val label: String,
  /** The field holding the reference, null for a GC root. */
  val fieldName: String?,
  /** The referring object, when it's in the tree and can therefore be inspected. */
  val inspectableObjectId: Long?,
  /**
   * How weakly this reference holds the object when it doesn't simply retain it: a
   * `java.lang.ref.Reference`'s referent, or an entry of a cache the explorer knows evicts. Null for a
   * reference that retains, which is nearly all of them.
   */
  val weakeningStrength: ReachabilityStrength? = null,
  /**
   * Whether the tree follows this reference. False only for a weakening one pointing at an object that
   * something stronger also holds, which is why nothing explains the object through it.
   */
  val isFollowed: Boolean = true
)
