package shark.dive

import shark.LeakTrace

/**
 * Everything pointing at one object of a heap dump: every object with a reference to it, and every GC root
 * on it. See [HeapDominatorTreemap.referrersOf].
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
  /**
   * Every GC root on the object itself. A root is no object, so it can't be one of [referrers]. Empty for an
   * object that is no root, which is most of them.
   */
  val gcRoots: List<ReferrerGcRoot>
) {

  /** How many of [referrers] the object is held through at least one reference of. */
  val holdingReferrerCount: Int get() = referrers.count { it.holds }

  companion object {
    val NONE = ObjectReferrers(referrers = emptyList(), gcRoots = emptyList())
  }
}

/** One object pointing at another, and every reference it points at it through. */
data class ObjectReferrer(
  /** The referring object, as a row of a list of objects is. */
  val entry: ObjectListEntry,
  /** Never empty. More than one for an object holding the same one in several fields. */
  val references: List<ReferrerReference>
) {

  /** Whether the dominator tree holds the object through any of [references]. */
  val holds: Boolean get() = references.any { it.holds }
}

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

/** One GC root on an object. See [ObjectReferrers.gcRoots]. */
data class ReferrerGcRoot(
  val gcRootType: LeakTrace.GcRootType,
  /**
   * Whether the dominator tree holds the object through this root, which is what [ReferrerReference.holds]
   * says of a reference. False for a weaker root on an object something firmer holds, such as a local
   * variable pointing at an object a field holds. See [HeapReachability.isHeldThrough].
   *
   * Shark also reads a local variable as a reference from its thread, when the heap dump names the thread.
   * So the same local can be a [ReferrerGcRoot] that holds nothing and an [ObjectReferrer] that holds.
   */
  val holds: Boolean
)
