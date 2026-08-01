package shark.explorer

import shark.ReferenceLocationType

/**
 * The one node a dominator tree attributes an object's bytes to. See
 * [HeapDominatorTreemap.dominatorOf].
 *
 * There is always exactly one: releasing this is what would free the object, and there is no second
 * answer. When no single object holds it — several holders on paths that meet nowhere — the dominator is
 * where the tree draws it rather than an object: the whole heap dump, or the pile of garbage. [kind] is
 * which.
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

  /**
   * Nothing in particular: the object is held from several places at once, or is a GC root itself, so the
   * tree draws it directly under the whole heap dump and that is where its bytes are attributed.
   */
  WHOLE_HEAP_DUMP,

  /** Nothing at all: no GC root reaches the object, so it's garbage waiting to be collected. */
  UNCOLLECTED_GARBAGE
}

/**
 * The ways an object is held below something above it that dominates it, each spelled out from below that
 * down to the object.
 *
 * Asked of the two ends of a stretch of a chain that could have run otherwise — see [RootPathDetour] — or of
 * the roots the tree was walked from, which is what the top of a chain is held by. Every path from a GC root
 * to the object goes through what dominates it, so these are every way it is held, with the part they all
 * share left out. They share no object in between either: **internally vertex-disjoint** paths, also called
 * independent paths, of which there are always at least two unless the upper end points straight at the
 * object — one alone would mean the object it goes through dominates it as well, and so is where the stretch
 * would have been cut. How many there are at most is the local vertex connectivity of the two, by Menger's
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

/** One way an object is held: a chain of references from where the search started down to it. */
data class IndependentPath(
  /**
   * Which kind of GC root the chain starts at, for a path found by
   * [HeapDominatorTreemap.independentPathsFromRoots]. Null for one found below an object, which is where
   * that chain starts instead.
   */
  val gcRootLabel: String?,
  /** From the step below where the search started down to the object itself, which is the last step. */
  val steps: List<PathStep>,
  /** How many steps between where it started and [steps], left out to keep the chain readable. */
  val hiddenStepCount: Int
)

/**
 * One object along an [IndependentPath], and the reference that reaches it.
 *
 * Everything a leak trace says about an object, because a path here is the same thing: what it is, how
 * firmly it's held, what it retains, what the inspectors make of it, and which field of the object above
 * points at it.
 */
data class PathStep(
  val objectId: Long,
  /** Fully qualified class name, or array type. */
  val className: String,
  val kind: HeapObjectKind,
  /**
   * What this kind of object is worth saying before anything else — a string's content, a bitmap's
   * dimensions — for the kinds the explorer recognizes, null for the rest. What tells one step of a path
   * apart from another step of the same class.
   */
  val headline: String?,
  val strength: ReachabilityStrength,
  /** Bytes retained, and how many objects that is: 0 for an object folded into another one. */
  val retainedSize: Long,
  val retainedCount: Int,
  /** What Shark's object inspectors have to say, e.g. that an activity is destroyed. */
  val inspectorLabels: List<String>,
  /** How the step before points at this one. Null for the first step of a path a GC root starts. */
  val reference: PathReference?,
  /** Whether the object is in the tree and can therefore be opened. */
  val isInspectable: Boolean
)

/** The reference from one step of a path to the next. See [PathStep.reference]. */
data class PathReference(
  /** The field's name, or the index for an array element. */
  val name: String,
  /** Simple name of the class that declares the field, which isn't always the referrer's own class. */
  val ownerClassName: String,
  val locationType: ReferenceLocationType
)
