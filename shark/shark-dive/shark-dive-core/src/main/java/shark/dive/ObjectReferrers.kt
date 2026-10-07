package shark.dive

import shark.LeakTrace

/**
 * Every object of a heap dump with a reference to one object, capped. See [HeapDominatorTreemap.referrersOf].
 *
 * One step up the graph, where [IndependentPaths] is the whole way up to the GC roots. The two answer
 * different questions. A set of independent paths shares no object in between, so two objects pointing at
 * this one and both held by a third are one path there, and here they are two referrers. And a path only
 * follows the references the dominator tree does, where this lists every reference and says which of them
 * the tree holds the object through.
 */
data class ObjectReferrers(
  /** The ones the object is held through first, then largest retained size first. */
  val referrers: List<ObjectReferrer>,
  /** How many objects point at it, which is more than [referrers] holds once it caps. */
  val referrerCount: Int,
  /**
   * How many of [referrerCount] the object is held through at least one reference of: the ones a path up
   * from it can take its first step to.
   */
  val holdingReferrerCount: Int,
  /**
   * The GC root holding the object itself, for one held from a root as well as by whatever is in
   * [referrers]. A root is no object, so this is how a list of what points at an object says one does.
   *
   * Only a root the tree was built from, like [IndependentPath.gcRootType], so that an object a local
   * variable also happens to point at isn't named after the local variable.
   */
  val gcRootType: LeakTrace.GcRootType?
) {

  /** Whether the cap left referrers out. */
  val hasMore: Boolean get() = referrers.size < referrerCount

  companion object {
    val NONE = ObjectReferrers(
      referrers = emptyList(),
      referrerCount = 0,
      holdingReferrerCount = 0,
      gcRootType = null
    )
  }
}

/** One object pointing at another, and every reference it points at it through. */
data class ObjectReferrer(
  /**
   * The referring object, read the way a step of a path is, with the verdict it has on its own. Its
   * [PathStep.reference] is null: what reaches the referrer is no part of this list.
   */
  val step: PathStep,
  /** Never empty. More than one for an object holding the same one in several fields. */
  val references: List<ReferrerReference>
)

/** One reference from a referrer to the object it points at. See [ObjectReferrer]. */
data class ReferrerReference(
  val reference: PathReference,
  /**
   * Whether the dominator tree holds the object through this reference, which is what every path and every
   * retained size here is read through.
   *
   * False for a reference that holds nothing: a weak reference to an object something else holds strongly,
   * or a reference to an object its owner already holds, such as any reference to a view but its parent's.
   * See [WeakeningAwareReferenceReader] and [OwnerReferences].
   */
  val holds: Boolean
)
