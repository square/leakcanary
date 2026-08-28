package shark.explorer

/**
 * The dominator tree under one node, in outline: the biggest few of what it dominates, and the biggest few of
 * what each of those dominates, a fixed number of levels down.
 *
 * The same tree the treemap, the rings and the stack all draw, without a viewport. Which is what makes it the
 * shape for a reader that has no screen — an agent — and what makes it worth being one function rather than
 * three: those three shapes each spend their pixels differently on the same answer, and where the memory is
 * doesn't depend on how it is drawn.
 *
 * Bounded in both directions because the root of a production heap dump has six figures of children and a
 * chain of single dominators runs to hundreds of levels. What was left out is counted rather than dropped
 * silently — [childCount] against the children handed back — so that a reader can tell "this is all of it"
 * from "this is the top of it".
 */
data class DominatorOutline(
  /** What to ask about this node next, and what a link to it names: an object's address, or a pile's id. */
  val nodeId: Long,
  /** How the window labels this node: `MainActivity`, `42 × Bitmap`, the whole heap dump. */
  val label: String,
  /** Bytes it retains, which is its own shallow size plus everything it dominates. */
  val retainedSize: Long,
  val strength: ReachabilityStrength,
  /**
   * How many objects this node stands for, for a node that is a pile of them rather than one object — a
   * class at the top of the tree, or the uncollected garbage. Null for one object, which is most nodes.
   */
  val objectCount: Int?,
  /** The class the pile is of, for a pile of one class. Null with [objectCount]. */
  val className: String?,
  /** How many nodes this one dominates directly, of which [children] is the largest few. */
  val childCount: Int,
  /** Largest retained size first, which is the order every list in this app is in. */
  val children: List<DominatorOutline>
)

/**
 * The outline of the dominator tree under [nodeId], largest first. See [DominatorOutline].
 *
 * Reads the heap dump once per node it names, so it belongs on the heap dump's thread like every other read
 * here, and the two bounds are what keep that a bounded number of reads: [maxDepth] levels of at most
 * [maxChildren] nodes each.
 *
 * @throws IllegalArgumentException for a node this tree hasn't got, the way every other question here does.
 */
fun HeapDominatorTreemap.outlineOf(
  nodeId: Long = HeapDominatorTreemap.ROOT_OBJECT_ID,
  /** How many levels below [nodeId] to walk. Zero is the node on its own, with its children counted. */
  maxDepth: Int = DEFAULT_OUTLINE_DEPTH,
  /** How many children of each node to walk into, the largest first. */
  maxChildren: Int = DEFAULT_OUTLINE_CHILDREN
): DominatorOutline {
  require(nodeId == HeapDominatorTreemap.ROOT_OBJECT_ID || nodeId in this) {
    "${hexObjectId(nodeId)} is no node of this heap dump's dominator tree"
  }
  val childIds = children(nodeId)
  val group = groupOrNull(nodeId)
  return DominatorOutline(
    nodeId = nodeId,
    label = label(nodeId),
    retainedSize = weight(nodeId),
    strength = strengthOf(nodeId),
    objectCount = group?.objectCount,
    className = group?.className,
    childCount = childIds.size,
    children = if (maxDepth <= 0) {
      emptyList()
    } else {
      // Sorted here rather than trusted from the tree: what a level of this is worth is that the biggest
      // thing is first, and the order children come back in is the order they were found in.
      childIds.sortedByDescending { weight(it) }
        .take(maxChildren)
        .map { outlineOf(it, maxDepth - 1, maxChildren) }
    }
  )
}

/**
 * Three levels, which is what says where the memory is without saying what holds what.
 *
 * The top of a heap dump is a handful of classes; the level under it is the objects of one; the level under
 * that is the first thing that is somebody's own code. Deeper than that is a chain to walk with a chain, not
 * an outline to read.
 */
const val DEFAULT_OUTLINE_DEPTH = 3

/** And ten of each, which is more than the eye reads off a treemap and less than a screenful of text. */
const val DEFAULT_OUTLINE_CHILDREN = 10
