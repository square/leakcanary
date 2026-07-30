package shark.explorer

/**
 * The one node a dominator tree attributes an object's bytes to. See
 * [HeapDominatorTreemap.dominatorOf].
 *
 * There is always exactly one: releasing this is what would free the object, and there is no second
 * answer. When no single object holds it — several holders on paths that meet nowhere — the dominator is
 * one of the two halves of the tree rather than an object, which is what [kind] says.
 */
data class ObjectDominator(
  /** The node to open on the treemap, which is where the object's bytes are drawn. */
  val nodeId: Long,
  val label: String,
  /** Bytes the dominator retains, which include the object's own. */
  val retainedSize: Long,
  val kind: DominatorKind
)

/** What kind of node dominates an object. See [ObjectDominator]. */
enum class DominatorKind {

  /** One object of the heap dump, which is what dominates most of them. */
  OBJECT,

  /** Nothing in particular: the object is held from several places at once, or is a GC root itself. */
  ALL_GC_ROOTS,

  /** Nothing at all: no GC root reaches the object, so it's garbage waiting to be collected. */
  UNCOLLECTED_GARBAGE
}

/**
 * The ways an object is held below its dominator, each spelled out from the dominator down to it.
 *
 * Every path from a GC root to the object goes through its dominator, so these are every way it is held,
 * with the part they all share left out. They share no object in between either: **internally
 * vertex-disjoint** paths, also called independent paths, of which there are always at least two unless
 * the dominator points straight at the object — one alone would mean the object it goes through is a
 * closer dominator. How many there are at most is the local vertex connectivity of the two, by Menger's
 * theorem.
 *
 * A set of them isn't unique, and finding a largest one is a max flow problem; this searches greedily,
 * which is why [hasMore] says the search stopped rather than that these are all there are. Two chains that
 * cross-reference each other can also be reported as one path each, since a path is not told about the
 * references leaving it.
 */
data class IndependentPaths(
  /** Shortest first. */
  val paths: List<IndependentPath>,
  /**
   * Whether the search stopped with paths left to find, either because there were more than it shows or
   * because it gave up: a greedy search can block a node a later path needed.
   */
  val hasMore: Boolean
) {
  companion object {
    val NONE = IndependentPaths(paths = emptyList(), hasMore = false)
  }
}

/** One way an object is held: a chain of references from its dominator down to it. */
data class IndependentPath(
  /**
   * Which kind of GC root the chain starts at, for a path below [DominatorKind.ALL_GC_ROOTS] or
   * [DominatorKind.UNCOLLECTED_GARBAGE]. Null below an object, which is where the chain starts instead.
   */
  val gcRootLabel: String?,
  /** From the step below the dominator down to the object itself, which is the last step. */
  val steps: List<PathStep>,
  /** How many steps between the dominator and [steps], left out to keep the chain readable. */
  val hiddenStepCount: Int
)

/** One object along an [IndependentPath], and the reference that reaches it. */
data class PathStep(
  val objectId: Long,
  /** Short name, as drawn on a rectangle. */
  val label: String,
  /**
   * The field of the step before it that points at it, `[3]` for an array element, null for the first step
   * of a path a GC root starts.
   */
  val referenceName: String?,
  /** Whether the object is in the tree and can therefore be opened. */
  val isInspectable: Boolean
)
