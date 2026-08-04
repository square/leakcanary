package shark.explorer

import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongObjectMap
import java.util.concurrent.TimeUnit.NANOSECONDS
import shark.HeapDominatorTree
import shark.HeapGraph
import shark.ObjectDominators.DominatorNode
import shark.SharkLog
import shark.ValueHolder.Companion.NULL_REFERENCE

/**
 * A heap dump's domination read from the classes up: every object of the dump on the row of its class, and
 * above each row the classes of the objects dominating it.
 *
 * [HeapDominatorTreemap] draws the same domination from the roots down, and answers "what is this object
 * keeping alive". This is the other direction: **take every `byte[]` of the dump — what dominates them, by
 * class?** The bottom row of a column is one class, as wide as what its objects take up, and the row above it
 * splits that width by the class of whatever dominates each of them. So a column reads `byte[]` at the
 * bottom, `Bitmap` above it, `ImageView` above that, and how wide it is says how much of the heap's `byte[]`
 * bytes that accounts for.
 *
 * **A row is weighed by the objects at the bottom of its column, in their own bytes.** Which is the one
 * weighting that makes the rows comparable: retained size would count an object's bytes again on every row
 * above it, so the rows would add up to several times the heap and "a fifth of the dump" would mean nothing.
 * Shallow bytes add up to the dump exactly once, so [root] weighs what the dominator tree's root weighs, a
 * row's width is its share of the whole heap dump at every level, and a row's children cover it to the byte.
 *
 * A column stops where the objects it names are dominated by nothing in particular — see
 * [ReverseNodeKind.NO_OWNER] — or by uncollected garbage alone, [ReverseNodeKind.UNCOLLECTED_GARBAGE]. Both
 * are rows of their own rather than width left over, which is what keeps a row's children covering it.
 *
 * Built as it is read, like the tree it reverses. The classes cost one pass over every object of the dump, and
 * a row above one costs a read per object that row gathers: its dominator, for the class to put it under. What
 * it holds in memory between reads is **at most one entry per object of the heap dump in total**, however many
 * levels have been opened, because expanding a row hands its entries to the rows above it and drops its own.
 * See [ReverseNode.entries].
 *
 * Nodes are ids of its own, out of the range no object id can land in: the other end of the one
 * [HeapDominatorTreemap] takes its pile ids from. [isReverseNode] is which. The exception is [root], which
 * the two trees share — the whole heap dump is the whole heap dump in either reading.
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
class ReverseDominatorTree internal constructor(
  private val graph: HeapGraph,
  private val reachability: HeapReachability,
  private val dominatorTree: HeapDominatorTree,
  /** The dominator tree's nodes, for the shallow size of an object and for which objects are folded. */
  private val nodes: Map<Long, DominatorNode>,
  private val groupingClasses: GroupingClasses
) : HeapTree {

  /** The whole heap dump, which both trees of it hang off. See [HeapDominatorTreemap.ROOT_OBJECT_ID]. */
  override val root: Long get() = HeapDominatorTreemap.ROOT_OBJECT_ID

  /** Every row handed out so far, by its id. The root isn't one: it belongs to both trees and to neither. */
  private val rowsById = MutableLongObjectMap<ReverseNode>()

  private var nextId = FIRST_REVERSE_NODE_ID

  /**
   * The bottom row of every column: one per class, with every object of the dump on the one for its own
   * class.
   *
   * Computed on first use, because it is a pass over every object of the heap dump — seconds on a large one —
   * and the reader who never opens this view shouldn't pay for it.
   */
  private val classRows: ClassRows by lazy { gatherByClass() }

  override fun weight(node: Long): Long =
    if (node == root) classRows.byteCount else rowOf(node).weight

  override fun children(node: Long): List<Long> =
    if (node == root) classRows.ids else rowsAbove(rowOf(node))

  override fun contains(nodeId: Long): Boolean = when {
    nodeId == root -> true
    isReverseNode(nodeId) -> nodeId in rowsById
    else -> false
  }

  override fun label(nodeId: Long): String =
    if (nodeId == root) HeapDominatorTreemap.ROOT_LABEL else rowOf(nodeId).label()

  override fun pathToOpen(nodeId: Long): List<Long> {
    if (!contains(nodeId) || nodeId == root) {
      return listOf(root)
    }
    val row = rowOf(nodeId)
    val column = ArrayDeque(row.columnUp().map { it.id })
    // A row nothing holds has no row above it, so rooting the view there would draw one row and nothing
    // else: the view stops at the row under it with it selected inside, as [HeapDominatorTreemap.pathToOpen]
    // does for an object that dominates nothing. Every other row has children — a class row is the classes
    // of what dominates its objects, and one of those two is what a column with no classes left ends at.
    if (row.kind.stopsAColumn) {
      column.removeLast()
    }
    return listOf(root) + column
  }

  /**
   * Everything the details panel shows about one row: what it gathers, how much of the heap that is, and
   * the column it is part of.
   *
   * Cheap, unlike [HeapDominatorTreemap.summarize]: a row was read when it was laid out, so this is what
   * was already worked out plus the labels of the rows under it.
   */
  fun summarize(nodeId: Long): ReverseNodeSummary {
    if (nodeId == root) {
      return ReverseNodeSummary(
        nodeId = root,
        kind = ReverseNodeKind.WHOLE_HEAP_DUMP,
        label = HeapDominatorTreemap.ROOT_LABEL,
        className = null,
        strength = ReachabilityStrength.STRONG,
        objectCount = classRows.objectCount,
        byteCount = classRows.byteCount,
        column = emptyList()
      )
    }
    val row = rowOf(nodeId)
    return ReverseNodeSummary(
      nodeId = nodeId,
      kind = row.kind,
      label = row.label(),
      className = row.className,
      strength = row.strength,
      objectCount = row.objectCount,
      byteCount = row.weight,
      // The rows under it, nearest first — the order they are read in on screen, going down from this one —
      // and the whole heap dump at the end of them, which every column stands on.
      column = row.columnUp().dropLast(1).reversed().map { ReverseColumnStep(it.id, it.label()) } +
        ReverseColumnStep(root, HeapDominatorTreemap.ROOT_LABEL)
    )
  }

  override fun <C : LayoutCell<Long>> present(cells: List<C>): List<PresentedCell<C>> =
    cells.map { it.presented() }

  private fun <C : LayoutCell<Long>> C.presented(): PresentedCell<C> = when (val subject = subject) {
    is CellSubject.Node -> presentedRow(subject.node)
    is CellSubject.Group -> PresentedCell(
      cell = this,
      // Rows rather than objects, which is what the row above one is made of: its classes.
      label = "${subject.nodeCount} smaller ${if (subject.nodeCount == 1) "row" else "rows"}",
      content = CellContent.Leftover(strengthOf(subject.parent))
    )
    // Never reached: a row's children cover what it weighs, so no row has width of its own left over. Here
    // because the layout is free to ask, and answering with the row itself is what that width would be.
    is CellSubject.Own -> presentedRow(subject.node)
  }

  /**
   * Every row of this tree stands for a pile of objects rather than for one, the root included — which is
   * what a view draws differently, and the whole point of reading the tree this way.
   */
  private fun <C : LayoutCell<Long>> C.presentedRow(nodeId: Long): PresentedCell<C> {
    if (nodeId == root) {
      return PresentedCell(
        cell = this,
        label = HeapDominatorTreemap.ROOT_LABEL,
        content = CellContent.ObjectRow(
          kind = ReverseNodeKind.WHOLE_HEAP_DUMP,
          strength = ReachabilityStrength.STRONG,
          objectCount = classRows.objectCount
        )
      )
    }
    val row = rowOf(nodeId)
    return PresentedCell(
      cell = this,
      label = row.label(),
      content = CellContent.ObjectRow(row.kind, row.strength, row.objectCount)
    )
  }

  private fun strengthOf(nodeId: Long): ReachabilityStrength =
    if (nodeId == root) ReachabilityStrength.STRONG else rowOf(nodeId).strength

  /**
   * This tree's row for [nodeId].
   *
   * Not [MutableLongObjectMap.get] with a `!!`, whose bare NullPointerException is what asking this tree
   * about a node of the dominator tree beside it reads as, several frames below whatever asked.
   */
  private fun rowOf(nodeId: Long): ReverseNode = requireNotNull(rowsById[nodeId]) {
    "${nodeIdText(nodeId)} is no row of this tree, which has handed out ${rowsById.size} of them. Ask " +
      "contains() first."
  }

  /**
   * Puts every object of the heap dump on the row of its own class, weighed by what it takes up itself.
   *
   * Over [HeapGraph.objects] rather than over [nodes], because an object's class comes off the index it is
   * read from and looking it up again per object is what made the first version of this take minutes. The
   * objects with no node are the folded ones — a string's characters, an object array's elements where the
   * array is what owns them — whose bytes are counted in the object holding them, so counting them here
   * would count them twice.
   */
  private fun gatherByClass(): ClassRows {
    val startNanos = System.nanoTime()
    val builders = LinkedHashMap<Long, RowBuilder>()
    var objectCount = 0
    graph.objects.forEach { heapObject ->
      val node = nodes[heapObject.objectId] ?: return@forEach
      objectCount++
      val classId = groupingClasses.classIdOf(heapObject) ?: NO_CLASS_ID
      builders.getOrPut(classId) { RowBuilder(ReverseNodeKind.CLASS, classId) }
        .add(heapObject.objectId, node.shallowSize, reachability.strengthOf(heapObject))
    }
    val ids = register(builders.values, parentId = root, depth = 1)
    val byteCount = ids.sumOf { rowOf(it).weight }
    SharkLog.d {
      "Gathered ${formatObjectCount(objectCount)} onto ${ids.size} class rows, " +
        "${formatByteSize(byteCount)}, in ${NANOSECONDS.toMillis(System.nanoTime() - startNanos)} ms"
    }
    return ClassRows(ids = ids, byteCount = byteCount, objectCount = objectCount)
  }

  /**
   * The row above [row]: what dominates the objects it gathers, gathered by class in turn, plus the columns
   * that stop here.
   *
   * A read of one object per entry — its dominator, for the class to put it under — so this is what a level
   * of this tree costs. Worked out once per row and kept, and the entries it was worked out from are dropped:
   * a row that has been expanded is a row on screen and nothing more.
   *
   * Nothing is registered until the whole row is built, so that a read given up on half way leaves the tree
   * as it was rather than holding half a row. See the module's AGENTS.md on cancellation.
   */
  private fun rowsAbove(row: ReverseNode): List<Long> {
    row.children?.let { return it }
    val entries = row.entries
    if (entries == null) {
      row.children = emptyList()
      return emptyList()
    }
    val builders = LinkedHashMap<Long, RowBuilder>()
    // The two rows that stop a column are kept apart from the classes rather than under a made up class id,
    // because every long is somebody's address: an object id can be negative, and zero is the only one no
    // object has. See [HeapDominatorTreemap.isPileId].
    var noOwner: RowBuilder? = null
    var garbage: RowBuilder? = null
    entries.forEach { objectId, weight ->
      val dominatorId = dominatorTree.immediateDominatorOf(objectId)
      if (dominatorId == root) {
        // The column stops here: what holds the object is the whole heap dump, which is what the dominator
        // tree says when nothing in particular does, or nothing at all when the object is garbage.
        val strength = reachability.strengthOf(objectId)
        val builder = if (strength == ReachabilityStrength.UNREACHABLE) {
          garbage ?: RowBuilder(ReverseNodeKind.UNCOLLECTED_GARBAGE, NO_CLASS_ID).also { garbage = it }
        } else {
          noOwner ?: RowBuilder(ReverseNodeKind.NO_OWNER, NO_CLASS_ID).also { noOwner = it }
        }
        builder.add(objectId, weight, strength)
      } else {
        val dominator = graph.findObjectById(dominatorId)
        val classId = groupingClasses.classIdOf(dominator) ?: NO_CLASS_ID
        builders.getOrPut(classId) { RowBuilder(ReverseNodeKind.CLASS, classId) }
          .add(dominatorId, weight, reachability.strengthOf(dominator))
      }
    }
    val children = register(
      builders = builders.values + listOfNotNull(noOwner, garbage),
      parentId = row.id,
      depth = row.depth + 1
    )
    row.children = children
    row.entries = null
    return children
  }

  /**
   * Turns the rows worked out for one level into rows of this tree, heaviest first — the order every level
   * of the tree beside it is handed out in.
   */
  private fun register(
    builders: Collection<RowBuilder>,
    parentId: Long,
    depth: Int
  ): List<Long> {
    val built = builders
      .map { builder -> builder.build(nextRowId(), parentId, depth) }
      .sortedByDescending { it.weight }
    built.forEach { rowsById[it.id] = it }
    return built.map { it.id }
  }

  /** The next id to hand a row, out of the half of the pile id range this tree owns. */
  private fun nextRowId(): Long {
    val rowId = nextId--
    check(rowId >= SMALLEST_REVERSE_NODE_ID) {
      "This tree has handed out every row id there is room for between $SMALLEST_REVERSE_NODE_ID and " +
        "$FIRST_REVERSE_NODE_ID, which takes more rows than a heap dump has objects"
    }
    return rowId
  }

  private fun RowBuilder.build(
    nodeId: Long,
    parentId: Long,
    depth: Int
  ): ReverseNode {
    val heapClass = if (classId == NO_CLASS_ID) {
      null
    } else {
      checkNotNull(graph.findObjectById(classId).asClass) {
        "The class $objectCount objects were gathered under, ${hexObjectId(classId)}, is no class of the " +
          "heap dump"
      }
    }
    return ReverseNode(
      id = nodeId,
      kind = kind,
      parentId = parentId,
      depth = depth,
      className = heapClass?.name,
      simpleClassName = heapClass?.simpleName,
      strength = strength,
      objectCount = objectCount,
      weight = weight,
      entries = entries
    )
  }

  /** The rows of a column from [ReverseNode] up, which is what a row's own path down to the root is. */
  private fun ReverseNode.columnUp(): List<ReverseNode> {
    val column = ArrayDeque<ReverseNode>()
    var row: ReverseNode? = this
    while (row != null) {
      column.addFirst(row)
      row = if (row.parentId == root) null else rowOf(row.parentId)
    }
    return column
  }

  private fun ReverseNode.label(): String = when (kind) {
    ReverseNodeKind.WHOLE_HEAP_DUMP -> HeapDominatorTreemap.ROOT_LABEL
    // "1,204 × byte[]" rather than "byte[]": a count and a multiplication sign say this row is a pile of
    // objects and not one of them, on a block with room for nothing else.
    ReverseNodeKind.CLASS ->
      "${formatCount(objectCount)} ${HeapDominatorTreemap.CLASS_GROUP_LABEL_SEPARATOR} " +
        (simpleClassName ?: CLASS_OBJECTS_LABEL)
    ReverseNodeKind.NO_OWNER -> NO_OWNER_LABEL
    ReverseNodeKind.UNCOLLECTED_GARBAGE -> HeapDominatorTreemap.UNREACHABLE_LABEL
  }

  /** What [children] and [weight] answer for the root, worked out by the pass that gathers the classes. */
  private class ClassRows(
    val ids: List<Long>,
    /** Every object of the heap dump in its own bytes, which is the whole dump. */
    val byteCount: Long,
    val objectCount: Int
  )

  /**
   * One row of this tree: the objects of one class at one point of a column, or one of the two ways a column
   * stops.
   *
   * A row is drawn as wide as [weight], names itself with [objectCount] and [simpleClassName], and knows the
   * row under it so that a column can be read back down. Everything else about it is [entries].
   */
  private class ReverseNode(
    val id: Long,
    val kind: ReverseNodeKind,
    /** The row under this one, and [root] for a class row at the bottom of a column. */
    val parentId: Long,
    /** How many rows are under it: 1 for a class row of the objects themselves. */
    val depth: Int,
    val className: String?,
    val simpleClassName: String?,
    /** The strength most of [weight] is held at. See [RowBuilder.strength]. */
    val strength: ReachabilityStrength,
    /** How many objects this row gathers, which is what its label counts. */
    val objectCount: Int,
    val weight: Long,
    /**
     * The objects this row gathers, by how many of the bytes below it each of them holds.
     *
     * What the row above is worked out from, and dropped once it has been: which is what bounds this tree to
     * one live entry per object of the heap dump. An expanded row's entries are split among its children by
     * the class of their dominator, so the entries alive at any moment stand for disjoint sets of the objects
     * at the bottom of the columns — at most one entry each, whatever levels are open.
     *
     * Null for a row that stops a column, which has nothing above it to work out.
     */
    var entries: MutableLongLongMap?,
    /** Worked out on the first [rowsAbove] and kept: laying the view out again asks for it every time. */
    var children: List<Long>? = null
  )

  /**
   * One row of a level being worked out: what its objects add up to, and which of them they are.
   *
   * Built up as the level's objects are read and turned into a [ReverseNode] once they all have been, so
   * that a class is read for its name once per row rather than once per object gathered on it.
   */
  private class RowBuilder(
    val kind: ReverseNodeKind,
    /** The class the row gathers, and [NO_CLASS_ID] for the two kinds that gather none. */
    val classId: Long
  ) {
    /** Null for a row that stops a column: there is nothing above it for entries to be worked out for. */
    val entries: MutableLongLongMap? =
      if (kind.stopsAColumn) null else MutableLongLongMap()

    var weight = 0L
      private set

    private var stoppedObjectCount = 0

    private val bytesByStrength = LongArray(STRENGTHS.size)

    /** How many objects the row gathers, which for a row of one class is how many distinct ones. */
    val objectCount: Int get() = entries?.size ?: stoppedObjectCount

    /**
     * How firmly the row is held, which is the strength holding most of its bytes.
     *
     * One answer for many objects, because a row is one block of one colour. The objects of one class at one
     * point of a column are usually held the same way, and where they aren't, the bytes are what a reader is
     * looking at.
     */
    val strength: ReachabilityStrength
      get() {
        var strongest = 0
        for (index in 1 until bytesByStrength.size) {
          if (bytesByStrength[index] > bytesByStrength[strongest]) {
            strongest = index
          }
        }
        return STRENGTHS[strongest]
      }

    /** Puts [objectId] on this row with [weight] of the bytes below it. */
    fun add(
      objectId: Long,
      weight: Long,
      strength: ReachabilityStrength
    ) {
      this.weight += weight
      bytesByStrength[strength.ordinal] += weight
      val entries = entries
      if (entries == null) {
        stoppedObjectCount++
      } else {
        // Two objects of one row can have the same dominator, and then that dominator is one object of the
        // row above holding both of their bytes.
        entries[objectId] = entries.getOrDefault(objectId, 0L) + weight
      }
    }
  }

  companion object {
    /**
     * The first of the ids this tree hands out to its rows, which count down from there.
     *
     * The other end of the range [HeapDominatorTreemap] takes its pile ids from: those count up from
     * [Long.MIN_VALUE] and these down from just below the smallest id an object of a heap dump can have, so
     * that telling one tree's nodes from the other's stays a range check. See
     * [HeapDominatorTreemap.isPileId] for why it can't be a look at the sign.
     */
    internal const val FIRST_REVERSE_NODE_ID = Int.MIN_VALUE.toLong() - 1L

    /** And the last of them, which leaves the other half of the range to the dominator tree's piles. */
    private const val SMALLEST_REVERSE_NODE_ID = Long.MIN_VALUE / 2

    /** Whether [nodeId] is a row of a tree read from the classes up rather than a node of a dominator tree. */
    fun isReverseNode(nodeId: Long): Boolean =
      nodeId in SMALLEST_REVERSE_NODE_ID..FIRST_REVERSE_NODE_ID

    /**
     * What a row of objects nothing in particular holds is called.
     *
     * Not "whole heap dump", though that is what the dominator tree says holds them, because the whole heap
     * dump is already the row across the bottom of this view and two rows of one name read as one thing.
     */
    const val NO_OWNER_LABEL = "Nothing in particular"

    /**
     * What the row of the objects no class gathers is called: class objects in a heap dump without
     * `java.lang.Class`, which no Android dump is. See [GroupingClasses.classIdOf].
     */
    private const val CLASS_OBJECTS_LABEL = "class objects"

    /** No class of the heap dump, since no object of one is at address zero. */
    private const val NO_CLASS_ID = NULL_REFERENCE

    private val STRENGTHS = ReachabilityStrength.values()
  }
}

/** What the UI knows about one row of a [ReverseDominatorTree]. See [ReverseDominatorTree.summarize]. */
data class ReverseNodeSummary(
  /** What the tree knows this row by, e.g. to zoom into it. Not an object id. */
  val nodeId: Long,
  val kind: ReverseNodeKind,
  /** Short name, as drawn on the row. */
  val label: String,
  /** Fully qualified class name, or array type, for [ReverseNodeKind.CLASS] only. */
  val className: String?,
  /** How firmly the objects it gathers are held: the strength most of [byteCount] is at. */
  val strength: ReachabilityStrength,
  /** How many objects this row gathers. */
  val objectCount: Int,
  /**
   * The bytes the row is as wide as: what the objects at the bottom of its column take up, of the ones these
   * objects dominate. Their own bytes, for a row at the bottom of a column.
   */
  val byteCount: Long,
  /** The rows under this one, the one it stands on first, down to the whole heap dump. */
  val column: List<ReverseColumnStep>
) {

  /** How far up its column this row is: 0 for the whole heap dump, 1 for a row of objects of one class. */
  val depth: Int get() = column.size
}

/** One row under a [ReverseNodeSummary]: what that row says, and what to zoom back out to it by. */
data class ReverseColumnStep(
  val nodeId: Long,
  val label: String
)

/** Which kind of row a [ReverseNodeSummary] is about. */
enum class ReverseNodeKind {

  /** The whole heap dump, at the bottom of every column, which the class rows are gathered on. */
  WHOLE_HEAP_DUMP,

  /** Objects of one class: their own row, or the row of what dominates the objects below them. */
  CLASS,

  /**
   * Where a column stops because nothing in particular holds the objects below it — the dominator tree hangs
   * them off the whole heap dump, because they are held from more than one place and no one of those would
   * free them. See [DominatorKind.WHOLE_HEAP_DUMP].
   */
  NO_OWNER,

  /** And where it stops because only uncollected garbage does. */
  UNCOLLECTED_GARBAGE;

  /** Whether this is where a column ends, which is a row with nothing above it. */
  internal val stopsAColumn: Boolean
    get() = this == NO_OWNER || this == UNCOLLECTED_GARBAGE
}
