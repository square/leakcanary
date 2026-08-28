package shark.explorer

import shark.SharkLog

/**
 * A leaking status someone reading the heap dump set on one object, which wins over what Shark's inspectors
 * made of it. See [leakStatusesOf].
 *
 * The thing the inspectors can't know: whether an object being in memory is a problem is often a question
 * about the app rather than about the heap dump — a cache that is meant to hold what it holds, a singleton
 * that is supposed to outlive the screen, a destroyed activity that is deliberately kept for one more frame.
 * An inspector reads a field; a person reads the code.
 *
 * **The reason is not optional**, which is the whole of why this is worth keeping rather than a colour
 * someone toggled: a status with no reason is an assertion the next reader — a colleague, an agent, the same
 * person in a month — has no way to check, and one of those in a heap dump makes every other status in it
 * worth less.
 */
data class LeakStatusOverride(
  val objectId: Long,
  val status: LeakStatus,
  /** Why, in whoever set it's own words. */
  val reason: String
) {
  init {
    require(reason.isNotBlank()) {
      "${hexObjectId(objectId)} was set to $status with no reason. A status set by hand overrules what " +
        "the heap dump itself says, so what it is is no use to anyone without why."
    }
  }
}

/**
 * Every leaking status set by hand on one heap dump, by object id.
 *
 * A value rather than something the heap dump's tree holds, and passed into every question whose answer it
 * changes, because a tree is read from one thread while the window is composed on another: overrides that
 * lived in the tree would mean a chain drawn from one set of them and the row above it from another. As a
 * value, whatever asked has the answer to what it asked.
 *
 * Two of these are equal when they hold the same statuses, which is what lets a Compose effect be keyed on
 * one: setting a status is what makes the window read the heap dump again.
 */
class LeakStatusOverrides private constructor(private val byObjectId: Map<Long, LeakStatusOverride>) {

  /** Every one of them, in no particular order. */
  val all: Collection<LeakStatusOverride> get() = byObjectId.values

  val isEmpty: Boolean get() = byObjectId.isEmpty()

  /** What was set on [objectId], or null for an object nobody has said anything about. */
  operator fun get(objectId: Long): LeakStatusOverride? = byObjectId[objectId]

  /** These and [override], which replaces whatever was set on the same object. */
  fun with(override: LeakStatusOverride): LeakStatusOverrides = with(listOf(override))

  /** The same for several at once, which is what solving a conflict sets. See [LeakStatusConflict]. */
  fun with(overrides: List<LeakStatusOverride>): LeakStatusOverrides {
    if (overrides.isEmpty()) {
      return this
    }
    return LeakStatusOverrides(byObjectId + overrides.associateBy { it.objectId })
  }

  /** These without whatever was set on [objectId], which is what taking a status back off an object is. */
  fun without(objectId: Long): LeakStatusOverrides =
    if (objectId in byObjectId) LeakStatusOverrides(byObjectId - objectId) else this

  override fun equals(other: Any?): Boolean =
    this === other || (other is LeakStatusOverrides && byObjectId == other.byObjectId)

  override fun hashCode(): Int = byObjectId.hashCode()

  override fun toString(): String = "LeakStatusOverrides(${byObjectId.values})"

  companion object {
    /** Nothing set by hand, which is every heap dump nobody has read yet. */
    val NONE = LeakStatusOverrides(emptyMap())

    fun of(overrides: List<LeakStatusOverride>): LeakStatusOverrides = NONE.with(overrides)
  }
}

/**
 * One status set by hand that a new one disagrees with, and what solving the disagreement would set it to.
 *
 * Two statuses set by hand conflict when the path rules make them contradict each other, which is a
 * question about how the two objects are held rather than about either of them: everything a leaking object
 * holds is leaking, and everything holding an object that is still needed is still needed. So a leaking
 * object above and an object that is not leaking below are two statuses that cannot both be read off the
 * chain running through them, whichever of them a hand set. Above and below in the sense of [isAbove], which
 * is not every pair one of which reaches the other. See [leakStatusConflictsWith].
 */
class LeakStatusConflict(
  /** The status already set by hand, which the new one disagrees with. */
  val existing: LeakStatusOverride,
  /** What that object is, for a dialog that has to name it: `MainActivity instance`. */
  val objectName: String,
  /** Whether it holds the object being set, as against being held by it. */
  val isAbove: Boolean,
  /**
   * What it becomes if the new status is the one to keep: the opposite of what it was, with the reason
   * saying that this is why.
   *
   * The opposite rather than nothing at all, because a conflict is only ever between the two statuses that
   * are opposites — so agreeing with the new one is the same as being flipped, and a status taken off the
   * object instead would leave the reason someone typed nowhere.
   */
  val solved: LeakStatusOverride
)

/**
 * Which of [overrides] the new [override] would disagree with, once the path rules have run over both.
 *
 * Asked before a status is set, so that the window can offer the choice the reader has to make: keep the
 * new one and flip the others to agree, or leave the heap dump as it was. Nothing is decided here — this
 * only says what the disagreements are.
 *
 * A walk up the referrers per status already set, which is what asking whether one object is above another
 * costs: see [isAbove]. There are a handful of statuses in a heap dump, so this is a handful of walks, and it
 * belongs on the heap dump's thread like every other read.
 */
fun HeapDominatorTreemap.leakStatusConflictsWith(
  override: LeakStatusOverride,
  overrides: LeakStatusOverrides
): List<LeakStatusConflict> {
  val conflicts = overrides.all.mapNotNull { existing ->
    if (existing.objectId == override.objectId) {
      // Setting a status on an object that already has one replaces it, so it can't disagree with itself.
      return@mapNotNull null
    }
    // A leaking object above forces everything it holds to be leaking, so it disagrees with anything else
    // down here.
    val holdsIt = existing.status == LeakStatus.STUCK &&
      override.status != LeakStatus.STUCK &&
      isAbove(aboveObjectId = existing.objectId, belowObjectId = override.objectId)
    // And an object below that is still needed forces everything holding it to be needed too, so it
    // disagrees with anything else up here.
    val heldByIt = existing.status == LeakStatus.EXPECTED &&
      override.status != LeakStatus.EXPECTED &&
      isAbove(aboveObjectId = override.objectId, belowObjectId = existing.objectId)
    if (!holdsIt && !heldByIt) {
      return@mapNotNull null
    }
    LeakStatusConflict(
      existing = existing,
      objectName = objectNameOrNull(existing.objectId) ?: hexObjectId(existing.objectId),
      isAbove = holdsIt,
      solved = existing.solvedBy(
        override = override,
        overrideName = objectNameOrNull(override.objectId) ?: hexObjectId(override.objectId),
        isAbove = holdsIt
      )
    )
  }
  SharkLog.d {
    "Setting ${hexObjectId(override.objectId)} to ${override.status} disagrees with " +
      "${conflicts.size} of the ${overrides.all.size} statuses set by hand"
  }
  return conflicts
}

/**
 * Whether [aboveObjectId] is above [belowObjectId]: it holds it, and is not held by it in turn.
 *
 * **Both directions, because objects that hold each other are ordinary in a heap dump** rather than a corner
 * case: an `AsyncTask` holds the thread running it, that thread's stack frame holds the runnable the executor
 * wrapped the task in, and that runnable holds the task — three objects each of which reaches the other two.
 * [HeapDominatorTreemap.reaches] on its own answers yes whichever way it is asked about any of those pairs, so
 * a conflict worked out from one direction of it is a conflict reported with whichever direction was asked
 * first, which reads back to whoever set the status as the two objects the wrong way round.
 *
 * Neither of two objects on a loop is above the other. Which of them a chain shows first is decided by where
 * that chain enters the loop, so the graph doesn't order them and a conflict between them would be one of two
 * answers with nothing to pick between them. Nothing is lost by not reporting it: a chain that does put one
 * above the other still records the disagreement, which is a reason reading `Conflicts with`.
 */
private fun HeapDominatorTreemap.isAbove(
  aboveObjectId: Long,
  belowObjectId: Long
): Boolean {
  if (!reaches(aboveObjectId, belowObjectId)) {
    return false
  }
  if (reaches(belowObjectId, aboveObjectId)) {
    SharkLog.d {
      "${hexObjectId(aboveObjectId)} and ${hexObjectId(belowObjectId)} hold each other, so neither of them " +
        "is above the other"
    }
    return false
  }
  return true
}

/**
 * The same object set to the opposite status, because the one it disagreed with is the one being kept.
 *
 * The reason says which status was flipped and what it said, so that solving a conflict never loses what
 * someone typed: the status is the window's, the sentence under it is theirs.
 */
private fun LeakStatusOverride.solvedBy(
  override: LeakStatusOverride,
  overrideName: String,
  isAbove: Boolean
): LeakStatusOverride = LeakStatusOverride(
  objectId = objectId,
  status = when (status) {
    LeakStatus.STUCK -> LeakStatus.EXPECTED
    LeakStatus.EXPECTED -> LeakStatus.STUCK
    // Nothing to flip: an object nobody claims to know about overrules nothing, so it is never one of the
    // statuses a new one has to be solved against.
    LeakStatus.UNKNOWN -> error(
      "${hexObjectId(objectId)} is unknown, so it does not conflict with ${hexObjectId(override.objectId)}"
    )
  },
  // Both statuses in quotes, because they are labels rather than words of the sentence: "can be expected"
  // reads as a claim of the sentence and "can be \"Expected\"" as the status it is.
  reason = "so that $overrideName ${if (isAbove) "below" else "above"} this can be " +
    "\"${override.status.statusText}\", which it was set to. Was \"${status.statusText}\": $reason"
)
