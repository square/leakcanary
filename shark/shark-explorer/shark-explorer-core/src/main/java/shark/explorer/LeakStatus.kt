package shark.explorer

/**
 * Whether an object on a path is meant to still be in memory, worked out by Shark's object inspectors.
 *
 * Every chain the explorer draws carries these, not only the ones that turn out to be leaks: a chain from
 * a GC root down to a bitmap runs through a dozen objects, and which of them are supposed to be alive is
 * what says where along it something went wrong. The reason for the leak is between the last
 * [EXPECTED] object and the first [STUCK] one, because everything above the first is doing its job
 * and everything below the last is being kept alive by it.
 *
 * **These are the words the window shows, the files keep and an agent reads**, deliberately the same three
 * everywhere rather than Shark's `LEAKING` and `NOT_LEAKING` translated per surface. A person watching an
 * agent work and the agent itself have to be able to say the same thing about the same object, and a
 * vocabulary that changes at the edge of the process is one nobody can check across it. [LeakFingerprint] is
 * the one place that maps to `shark.LeakTraceObject.LeakingStatus`, because a fingerprint has to be the same
 * string LeakCanary computes.
 *
 * **And none of the three is built on "leak".** A leak is one faulty reference that should have been
 * cleared, and everything under it is retained by that one mistake — so a word like `Leaking` on twenty
 * objects points a reader at the twenty rather than at the one thing to fix.
 */
enum class LeakStatus {

  /** Something knows this object is still needed: a live activity, a class, a running thread. */
  EXPECTED,

  /** Nothing knows either way, which is most of a heap dump. */
  UNKNOWN,

  /**
   * Something knows this object should be gone: a destroyed activity, a watched object still there.
   *
   * **Including one nothing reaches any more**, which the leaks screen lists apart under
   * [LeakKind.UNREACHABLE]. It was still expected to be gone, and what keeps it here is only that the
   * garbage collector hasn't run — so this is the same verdict, said the same way, and where an object sits
   * on that scale is what the leaks screen is for rather than what this says.
   */
  STUCK
}

/**
 * The same word as a sentence reads it: on a chain, in the reason another object gives, in the row above the
 * panes. Only the case differs from the constant, which is what this exists for.
 *
 * In this module rather than in the window, because the reasons worked out here are sentences that name
 * statuses — a status set by hand says which status it was set from — and two spellings of one status
 * would show up in one line of one window.
 *
 * `Stuck` says what is true of the object without accusing it: it should be gone and something is holding
 * it, which is the question worth asking. `Expected` says its being in memory is legitimate at this point
 * in the app's life, which is what an inspector actually recognizes.
 *
 * No heap analyser has a verdict like this to borrow words from — JProfiler classifies objects by
 * reference type and by age, YourKit by reachability scope, and both leave the judgement to the reader,
 * because neither has watched objects or framework inspectors to make it with. What JProfiler's prose asks
 * is whether objects "are still legitimately on the heap or if a faulty reference keeps them alive", which
 * is the same split these two words are, and where the **faulty reference** gets its name.
 */
val LeakStatus.statusText: String
  get() = when (this) {
    LeakStatus.EXPECTED -> "Expected"
    LeakStatus.UNKNOWN -> "Unknown"
    LeakStatus.STUCK -> "Stuck"
  }

/** What one object of a path is, and why. See [LeakStatus]. */
internal class LeakStatusAndReason(
  val status: LeakStatus,
  /**
   * In words, e.g. `Activity#mDestroyed is true`. Null for [LeakStatus.UNKNOWN], unless a hand set it: an
   * object someone said nothing is known about has a reason for that too.
   */
  val reason: String?
)

/** What Shark's inspectors made of one object of a path, before the path decides what it means. */
internal class InspectedPathObject(
  /** For naming it in another object's reason: `MainActivity↓ is expected`. */
  val simpleClassName: String,
  val leakingReasons: Set<String>,
  val notLeakingReasons: Set<String>,
  /**
   * What someone reading this heap dump decided this object is, which wins over the reasons above it.
   * Null for every object nobody has said anything about, which is all of them to start with.
   */
  val setByHand: LeakStatusOverride? = null
)

/**
 * What each object of a path is, from what the inspectors said about each of them **and about the ones
 * above and below it**, which is where most of the answer comes from.
 *
 * Two rules, both of them about the path rather than the object: everything above an object that is not
 * leaking is not leaking either, because it is holding something that is still needed; and everything
 * below a leaking object is leaking, because the only thing keeping it in memory is an object that
 * shouldn't be there. So the inspectors have to recognize one object of a chain for the whole chain to
 * read, and what's left in the middle — between the last [LeakStatus.EXPECTED] and the first
 * [LeakStatus.STUCK] — is where the **faulty reference** is: the one reference that should have been
 * cleared, and the whole of what there is to fix.
 *
 * This is [shark.RealLeakTracerFactory]'s algorithm, kept in step with it deliberately: a chain here and
 * a LeakCanary leak trace of the same objects that disagreed about which of them are leaking would be two
 * answers to the same question. One rule of it is left out — **the object a path ends at is not forced to
 * be leaking**. A leak trace ends where the leak is, so forcing it is right there; a path here ends
 * wherever the reader clicked, and calling whatever that was leaking would be the window inventing leaks.
 *
 * A status someone set by hand is what that object is — see [setByHandStatus] — and then these two rules
 * run over it like over any other: what an object is decides what the objects above and below it are, and
 * that is as true of an object a person recognized as of one an inspector did. Which is what makes two
 * statuses set by hand able to disagree, and [leakStatusConflictsWith] what finds it before they do.
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
    if (status.status == LeakStatus.EXPECTED) {
      lastNotLeakingIndex = index
      // So that the first leaking object is never above the last one that isn't: an object that is
      // leaking and is held by something that isn't means the leak starts below it.
      firstLeakingIndex = lastIndex
    } else if (status.status == LeakStatus.STUCK && firstLeakingIndex == lastIndex) {
      firstLeakingIndex = index
    }
  }
  for (index in 0 until lastNotLeakingIndex) {
    val nextNotLeakingIndex = (index + 1..lastNotLeakingIndex)
      .first { statuses[it].status == LeakStatus.EXPECTED }
    val nextNotLeakingName = "${objects[nextNotLeakingIndex].simpleClassName}↓"
    val reason = statuses[index].reason
    statuses[index] = LeakStatusAndReason(
      status = LeakStatus.EXPECTED,
      reason = when (statuses[index].status) {
        // With a reason of its own only when a hand gave it one, which the path is then overruling: an
        // object someone said nothing is known about is one of the two statuses this can disagree with.
        LeakStatus.UNKNOWN -> "$nextNotLeakingName is expected".conflicting(reason)
        LeakStatus.EXPECTED -> "$nextNotLeakingName is expected and $reason"
        LeakStatus.STUCK -> "$nextNotLeakingName is expected. Conflicts with $reason"
      }
    )
  }
  for (index in lastIndex downTo firstLeakingIndex + 1) {
    val previousLeakingIndex = (index - 1 downTo firstLeakingIndex)
      .first { statuses[it].status == LeakStatus.STUCK }
    val previousLeakingName = "${objects[previousLeakingIndex].simpleClassName}↑"
    val reason = statuses[index].reason
    statuses[index] = LeakStatusAndReason(
      status = LeakStatus.STUCK,
      reason = when (statuses[index].status) {
        LeakStatus.UNKNOWN -> "$previousLeakingName is stuck".conflicting(reason)
        LeakStatus.STUCK -> "$previousLeakingName is stuck and $reason"
        // No object below the first leaking one is left not leaking: the first leaking index is reset
        // past every object that isn't, and the loop above turned the rest into not leaking already.
        LeakStatus.EXPECTED -> error(
          "${objects[index].simpleClassName} at $index is expected, below " +
            "${objects[previousLeakingIndex].simpleClassName} at $previousLeakingIndex, which is stuck"
        )
      }
    )
  }
  return statuses
}

/**
 * Which references of a path the leak could be, as indexes into it: the stretch [leakStatusesOf] leaves in
 * the middle, from the last object expected to be in memory down to the first stuck one.
 *
 * **What a leak is named after**, and what makes two objects instances of the same leak — the same stretch
 * LeakCanary underlines in a leak trace and hashes into a leak fingerprint. The fault is at one of these
 * references and the objects between them are the ones nothing knows either way about, so which one of them
 * it is only reads off the path when there is a single one: that one is [faultyReferenceIndexOrNull], and
 * narrowing a longer stretch to it is a person reading code, which is what setting a status by hand is for.
 *
 * Empty for a path with nothing stuck on it, which is most paths of a heap dump: the rules can point at a
 * reference only once something below it is known not to belong. And the references of steps that have none
 * are left out, which is the object a GC root reaches: a root holds its object through no field, so there is
 * nothing there to have cleared.
 */
internal fun List<PathStep>.suspectReferenceIndexes(): List<Int> {
  val firstStuck = indexOfFirst { it.leakStatus == LeakStatus.STUCK }
  if (firstStuck == -1) {
    return emptyList()
  }
  // Never below the first stuck object: [leakStatusesOf] pushes that one past every object expected to be
  // in memory, so the stretch between the two ends is never empty and never runs backwards.
  val lastExpected = indexOfLast { it.leakStatus == LeakStatus.EXPECTED }
  return (lastExpected + 1..firstStuck).filter { this[it].reference != null }
}

/**
 * Which reference of a path is **the faulty reference** — the one that should have been cleared — as an index
 * into it, or null when the path doesn't say which one that is.
 *
 * The single reference that crosses from an object expected to be in memory to a stuck one: above it
 * everything is legitimately held, below it everything is in memory only because of it, so that one
 * reference is the whole of what there is to change in code.
 *
 * Null for most paths, and the three ways it is null are worth telling apart:
 *
 * - **Nothing stuck on the path.** There is no fault to point at, which is most of a heap dump.
 * - **Nothing expected above the stuck object**, a chain of `Cleaner`s no inspector recognizes being the
 *   shape of it. What holds the stuck object may be something that should have let go of it too, and then
 *   the fault is further up than this path knows — so marking the top of the path would be naming a
 *   reference for being where the walk stopped.
 * - **Objects nothing is known about in between.** The fault is at one of those steps and the path doesn't
 *   say which, so marking one of them would be a guess drawn as an answer. They are
 *   [suspectReferenceIndexes], which is what a leak is named after, and a status set by hand is what turns
 *   that stretch into a single reference.
 */
internal fun List<PathStep>.faultyReferenceIndexOrNull(): Int? {
  val firstStuck = indexOfFirst { it.leakStatus == LeakStatus.STUCK }
  val lastExpected = indexOfLast { it.leakStatus == LeakStatus.EXPECTED }
  if (firstStuck == -1 || lastExpected == -1 || firstStuck != lastExpected + 1) {
    return null
  }
  // A step below the first can still be missing its reference, when reading the referrer again doesn't find
  // the reference the referrer index walked through — see `stepTo`. Nothing to mark then.
  return firstStuck.takeIf { this[firstStuck].reference != null }
}

/** The same sentence with what it is overruling recorded after it, when there is anything to record. */
private fun String.conflicting(overruled: String?): String =
  if (overruled == null) this else "$this. Conflicts with $overruled"

/**
 * What the inspectors said about one object, on its own, and what an object both sides recognize is:
 * still needed, unless it is the object the path is about.
 *
 * Unless a hand set it, in which case that is the answer and the inspectors are what it is recorded as
 * disagreeing with. See [setByHandStatus].
 */
private fun InspectedPathObject.ownStatus(leakingWins: Boolean): LeakStatusAndReason {
  val notLeaking = notLeakingReasons.joinToString(" and ").takeIf { notLeakingReasons.isNotEmpty() }
  val leaking = leakingReasons.joinToString(" and ").takeIf { leakingReasons.isNotEmpty() }
  if (setByHand != null) {
    return setByHandStatus(setByHand, leaking = leaking, notLeaking = notLeaking)
  }
  return when {
    leaking != null && notLeaking != null -> if (leakingWins) {
      LeakStatusAndReason(LeakStatus.STUCK, "$leaking. Conflicts with $notLeaking")
    } else {
      LeakStatusAndReason(LeakStatus.EXPECTED, "$notLeaking. Conflicts with $leaking")
    }
    leaking != null -> LeakStatusAndReason(LeakStatus.STUCK, leaking)
    notLeaking != null -> LeakStatusAndReason(LeakStatus.EXPECTED, notLeaking)
    else -> LeakStatusAndReason(LeakStatus.UNKNOWN, null)
  }
}

/**
 * What an object someone set the status of by hand is: whatever they said, whoever says otherwise.
 *
 * **Overriding always wins**, which is the one place this differs from how two inspectors disagreeing is
 * settled: there the object being still needed wins, because two inspectors are two pieces of the same
 * automated reading and the safer of them is the one to believe. A hand is not that — someone who has read
 * the heap dump and typed a reason knows something the inspectors don't, and a rule that weighed the two
 * would mean a status that can't be changed to the one the inspectors already picked.
 *
 * So the inspectors become the record of what was overruled, the way a conflict between two of them is
 * recorded, and the reason is the one that was typed.
 */
private fun setByHandStatus(
  setByHand: LeakStatusOverride,
  leaking: String?,
  notLeaking: String?
): LeakStatusAndReason {
  val overruled = when (setByHand.status) {
    LeakStatus.STUCK -> notLeaking
    LeakStatus.EXPECTED -> leaking
    // Both of them, since saying nothing is known about an object overrules anything that claimed to know.
    LeakStatus.UNKNOWN -> listOfNotNull(notLeaking, leaking).joinToString(" and ").takeIf { it.isNotEmpty() }
  }
  return LeakStatusAndReason(
    status = setByHand.status,
    reason = "$SET_BY_HAND${setByHand.reason}".conflicting(overruled)
  )
}

/**
 * In front of the reason someone typed, wherever their status is read.
 *
 * Because the reason is the whole of what a chain says about an object, and a status a hand set has to be
 * readable as one there: half the objects of a chain are green or red because of an inspector, and which of
 * them is there because someone decided so is the difference between reading the heap dump and reading
 * someone's conclusion about it.
 */
internal const val SET_BY_HAND = "set by hand — "
