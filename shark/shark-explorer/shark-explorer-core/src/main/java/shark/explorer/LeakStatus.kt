package shark.explorer

/**
 * Whether an object on a path is meant to still be in memory, worked out by Shark's object inspectors.
 *
 * Every chain the explorer draws carries these, not only the ones that turn out to be leaks: a chain from
 * a GC root down to a bitmap runs through a dozen objects, and which of them are supposed to be alive is
 * what says where along it something went wrong. The reason for the leak is between the last
 * [NOT_LEAKING] object and the first [LEAKING] one, because everything above the first is doing its job
 * and everything below the last is being kept alive by it.
 */
enum class LeakStatus {

  /** Something knows this object is still needed: a live activity, a class, a running thread. */
  NOT_LEAKING,

  /** Nothing knows either way, which is most of a heap dump. */
  UNKNOWN,

  /** Something knows this object should be gone: a destroyed activity, a watched object still there. */
  LEAKING
}

/** What one object of a path is, and why. See [LeakStatus]. */
internal class LeakStatusAndReason(
  val status: LeakStatus,
  /** In words, e.g. `Activity#mDestroyed is true`. Null for [LeakStatus.UNKNOWN]. */
  val reason: String?
)

/** What Shark's inspectors made of one object of a path, before the path decides what it means. */
internal class InspectedPathObject(
  /** For naming it in another object's reason: `MainActivity↓ is not leaking`. */
  val simpleClassName: String,
  val leakingReasons: Set<String>,
  val notLeakingReasons: Set<String>
)

/**
 * What each object of a path is, from what the inspectors said about each of them **and about the ones
 * above and below it**, which is where most of the answer comes from.
 *
 * Two rules, both of them about the path rather than the object: everything above an object that is not
 * leaking is not leaking either, because it is holding something that is still needed; and everything
 * below a leaking object is leaking, because the only thing keeping it in memory is an object that
 * shouldn't be there. So the inspectors have to recognize one object of a chain for the whole chain to
 * read, and what's left in the middle — between the last [LeakStatus.NOT_LEAKING] and the first
 * [LeakStatus.LEAKING] — is where the leak is.
 *
 * This is [shark.RealLeakTracerFactory]'s algorithm, kept in step with it deliberately: a chain here and
 * a LeakCanary leak trace of the same objects that disagreed about which of them are leaking would be two
 * answers to the same question. One rule of it is left out — **the object a path ends at is not forced to
 * be leaking**. A leak trace ends where the leak is, so forcing it is right there; a path here ends
 * wherever the reader clicked, and calling whatever that was leaking would be the window inventing leaks.
 */
internal fun leakStatusesOf(objects: List<InspectedPathObject>): List<LeakStatusAndReason> {
  if (objects.isEmpty()) {
    return emptyList()
  }
  val lastIndex = objects.lastIndex
  // A conflict is resolved in favour of the object still being needed, except at the end of the path:
  // that one is the object being asked about, so what is known to be wrong with it is the answer.
  val statuses = objects.mapIndexed { index, inspected ->
    inspected.ownStatus(leakingWins = index == lastIndex)
  }.toMutableList()
  var lastNotLeakingIndex = -1
  var firstLeakingIndex = lastIndex
  statuses.forEachIndexed { index, status ->
    if (status.status == LeakStatus.NOT_LEAKING) {
      lastNotLeakingIndex = index
      // So that the first leaking object is never above the last one that isn't: an object that is
      // leaking and is held by something that isn't means the leak starts below it.
      firstLeakingIndex = lastIndex
    } else if (status.status == LeakStatus.LEAKING && firstLeakingIndex == lastIndex) {
      firstLeakingIndex = index
    }
  }
  for (index in 0 until lastNotLeakingIndex) {
    val nextNotLeakingIndex = (index + 1..lastNotLeakingIndex)
      .first { statuses[it].status == LeakStatus.NOT_LEAKING }
    val nextNotLeakingName = "${objects[nextNotLeakingIndex].simpleClassName}↓"
    val reason = statuses[index].reason
    statuses[index] = LeakStatusAndReason(
      status = LeakStatus.NOT_LEAKING,
      reason = when (statuses[index].status) {
        LeakStatus.UNKNOWN -> "$nextNotLeakingName is not leaking"
        LeakStatus.NOT_LEAKING -> "$nextNotLeakingName is not leaking and $reason"
        LeakStatus.LEAKING -> "$nextNotLeakingName is not leaking. Conflicts with $reason"
      }
    )
  }
  for (index in lastIndex downTo firstLeakingIndex + 1) {
    val previousLeakingIndex = (index - 1 downTo firstLeakingIndex)
      .first { statuses[it].status == LeakStatus.LEAKING }
    val previousLeakingName = "${objects[previousLeakingIndex].simpleClassName}↑"
    val reason = statuses[index].reason
    statuses[index] = LeakStatusAndReason(
      status = LeakStatus.LEAKING,
      reason = when (statuses[index].status) {
        LeakStatus.UNKNOWN -> "$previousLeakingName is leaking"
        LeakStatus.LEAKING -> "$previousLeakingName is leaking and $reason"
        // No object below the first leaking one is left not leaking: the first leaking index is reset
        // past every object that isn't, and the loop above turned the rest into not leaking already.
        LeakStatus.NOT_LEAKING -> error(
          "${objects[index].simpleClassName} at $index is not leaking, below the leaking " +
            "${objects[previousLeakingIndex].simpleClassName} at $previousLeakingIndex"
        )
      }
    )
  }
  return statuses
}

/**
 * What the inspectors said about one object, on its own, and what an object both sides recognize is:
 * still needed, unless it is the object the path is about.
 */
private fun InspectedPathObject.ownStatus(leakingWins: Boolean): LeakStatusAndReason {
  val notLeaking = notLeakingReasons.joinToString(" and ").takeIf { notLeakingReasons.isNotEmpty() }
  val leaking = leakingReasons.joinToString(" and ").takeIf { leakingReasons.isNotEmpty() }
  return when {
    leaking != null && notLeaking != null -> if (leakingWins) {
      LeakStatusAndReason(LeakStatus.LEAKING, "$leaking. Conflicts with $notLeaking")
    } else {
      LeakStatusAndReason(LeakStatus.NOT_LEAKING, "$notLeaking. Conflicts with $leaking")
    }
    leaking != null -> LeakStatusAndReason(LeakStatus.LEAKING, leaking)
    notLeaking != null -> LeakStatusAndReason(LeakStatus.NOT_LEAKING, notLeaking)
    else -> LeakStatusAndReason(LeakStatus.UNKNOWN, null)
  }
}
