package shark.dive

import shark.LeakTrace

/**
 * Everything pointing at one object of a heap dump, a page at a time: every object with a reference to it,
 * and every GC root on it. See [HeapDominatorTreemap.referrersOf].
 *
 * One step up the graph, where [IndependentPaths] is the whole way up to the GC roots. The two answer
 * different questions. A set of independent paths shares no object in between, so two objects pointing at
 * this one and both held by a third are one path there, and here they are two referrers. And a path only
 * follows the references the dominator tree does, where this lists every reference and says which of them
 * the tree holds the object through.
 */
data class ObjectReferrers(
  /**
   * One page of the referrers. The ones the object is held through come first, a GC root ahead of an object,
   * then the largest retained size first.
   */
  val referrers: List<Referrer>,
  /** Where [referrers] starts in the whole list. */
  val offset: Int,
  /** How many referrers there are, GC roots included, however many [referrers] lists. */
  val referrerCount: Int,
  /** How many of every referrer hold the object, GC roots included, however many [referrers] lists. */
  val holdingReferrerCount: Int
) {

  /** Where the page after this one starts, or null for the last page. */
  val nextOffset: Int? get() = (offset + referrers.size).takeIf { it < referrerCount }

  companion object {
    val NONE = ObjectReferrers(referrers = emptyList(), offset = 0, referrerCount = 0, holdingReferrerCount = 0)
  }
}

/** One thing pointing at an object: another object, or a GC root on it. See [ObjectReferrers]. */
sealed interface Referrer {

  /**
   * Whether the dominator tree holds the object through this, which is what every path and every retained
   * size here is read through. See [ReferrerReference.holds] and [GcRootReferrer.holds].
   */
  val holds: Boolean
}

/** One object pointing at another, and every reference it points at it through. */
data class ObjectReferrer(
  /** The referring object, as a row of a list of objects is. */
  val entry: ObjectListEntry,
  /** Never empty. More than one for an object holding the same one in several fields. */
  val references: List<ReferrerReference>
) : Referrer {

  /** Whether the dominator tree holds the object through any of [references]. */
  override val holds: Boolean get() = references.any { it.holds }
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

/** One GC root on an object. A root is no object, so it has a type and no row or references. */
data class GcRootReferrer(
  val gcRootType: LeakTrace.GcRootType,
  /**
   * Whether the dominator tree holds the object through this root, which is what [ReferrerReference.holds]
   * says of a reference. False for a weaker root on an object something firmer holds, such as a local
   * variable pointing at an object a field holds. See [HeapReachability.isHeldThrough].
   *
   * Shark also reads a local variable as a reference from its thread, when the heap dump names the thread.
   * So the same local can be a [GcRootReferrer] that holds nothing and an [ObjectReferrer] that holds.
   */
  override val holds: Boolean
) : Referrer
