package shark.dive

import java.security.MessageDigest

/**
 * Every stuck object of a heap dump, gathered into the leaks they are instances of. See
 * [HeapDominatorTreemap.findLeaks].
 *
 * The same answer LeakCanary's analysis gives, computed here instead so that every row keeps the object id
 * it came from: a leak trace names classes, and a screen you can click through has to name objects. Which
 * is also why there is no leak trace here — the chain the object view already draws is one, so a leak
 * is a list of objects to open and Shark Dive does the rest.
 */
data class HeapLeaks(
  /** In [LeakKind] order, all of them, empty ones included: an empty section is an answer. */
  val sections: List<LeakSection>
) {

  /** How many leaking objects there are, which is what the button leading here says. */
  val objectCount: Int get() = sections.sumOf { it.objectCount }

  /** The sections that are leaks to do something about, which is the half of the screen that leads it. */
  val leakSections: List<LeakSection> get() = sections.filter { !it.kind.isOnTheWayOut }

  /** And the ones nobody has to do anything about. See [LeakKind.isOnTheWayOut]. */
  val onTheWayOutSections: List<LeakSection> get() = sections.filter { it.kind.isOnTheWayOut }

  /** How many objects are leaks to do something about, which is what the screen leads with. */
  val leakingObjectCount: Int get() = leakSections.sumOf { it.objectCount }

  /** Every leaking object by id, which is what the treemap shades leaks from. */
  val leakingObjectIds: Set<Long>
    get() = sections.flatMapTo(mutableSetOf()) { section ->
      section.groups.flatMap { group -> group.objects.map { it.objectId } }
    }

  companion object {
    /** Nothing found yet, which is what the screen shows while the pass over the heap dump runs. */
    val NONE = HeapLeaks(emptyList())
  }
}

/**
 * Which kind of thing a leak is, which is what splits the screen into sections.
 *
 * The split is what makes the list actionable, and it is a split in two halves. The first two are leaks to
 * do something about: the app's own are the ones to go and fix, the library ones are somebody else's and
 * are mostly there so they don't get mistaken for the app's. The rest are objects that shouldn't be in
 * memory and are on their way out of it anyway, a section per way — the garbage collector clears every one
 * of these strengths on its own, so nothing in the app has to change for the bytes to come back.
 *
 * LeakCanary reports none of that second half and can't tell one from another: its analysis follows no
 * soft, weak or phantom referent, so everything below the rule here is an object no GC root reaches as far
 * as it is concerned, and it drops the lot without saying so. Which is the reason to spell them out rather
 * than leave them off — an object that was meant to be gone and is on its way is an answer, and it is a
 * different answer from each of the others.
 *
 * **Declared in [ReachabilityStrength] order** after the first two, weakest last, which is the order the
 * sections are drawn in.
 */
enum class LeakKind(
  val title: String,
  /** What being in this section means, since not one of the titles says it on its own. */
  val explanation: String,
  /**
   * How firmly the objects of this section are held, for the sections that are about that. Null for
   * [APPLICATION] and [LIBRARY], which are about the reference that holds an object rather than how
   * firmly: those two hold everything the app itself has to let go of, whether that is an ordinary
   * reference, a cache, a thread's storage or a stack frame.
   */
  val strength: ReachabilityStrength? = null,
  /**
   * What a group of these says when there is no reference to name it after, which is [UNREACHABLE] and
   * nothing else: every other section's groups are a reference, and a reference says what a sentence
   * would have.
   */
  val subtitle: String? = null
) {

  APPLICATION(
    "App leaks",
    "Objects the app itself keeps in memory after it was done with them. Each of these is a leak to fix, " +
      "in code the app controls."
  ),

  LIBRARY(
    "Library leaks",
    "The same thing in code the app doesn't control: the Android framework or a library. Shark recognizes " +
      "the reference that holds them, so they can be told apart from the app's own — there is usually " +
      "nothing to do about them but wait for a fix upstream."
  ),

  SOFT(
    "Softly reachable",
    "Objects a soft reference is the last thing holding, which the virtual machine clears when it wants " +
      "the memory back. So they stay until memory runs short, and then go without the app doing anything.",
    strength = ReachabilityStrength.SOFT
  ),

  WEAK(
    "Weakly reachable",
    "Objects a weak reference is the last thing holding. The next garbage collection clears it and takes " +
      "them, whether or not memory is short.",
    strength = ReachabilityStrength.WEAK
  ),

  FINALIZER(
    "Waiting to be finalized",
    "Objects whose class has a `finalize()` method, reachable only from the queue of objects waiting for " +
      "it to run. They survive one more collection at least, and longer if finalization is backed up.",
    strength = ReachabilityStrength.FINALIZER
  ),

  PHANTOM(
    "Phantom reachable",
    "Objects already finalized and out of the app's reach, held only so that a `Cleaner` or a phantom " +
      "reference gets to run. On Android that is nearly always a `Cleaner` freeing native memory, which " +
      "the runtime drains on its own.",
    strength = ReachabilityStrength.PHANTOM
  ),

  UNREACHABLE(
    "Unreachable",
    "Objects that were meant to be gone and are: nothing reaches them any more, and the next garbage " +
      "collection would take them. Listed so that a heap dump whose leaks have all been collected doesn't " +
      "read as a heap dump nothing looked at.",
    strength = ReachabilityStrength.UNREACHABLE,
    subtitle = "Nothing reaches these any more: the next garbage collection would take them."
  );

  /**
   * Whether it is one of the sections nobody has to do anything about, which is the second half of the
   * screen and is drawn folded under one heading. True of exactly the sections a [strength] names: those
   * are the objects the collector takes on its own.
   */
  val isOnTheWayOut: Boolean get() = strength != null

  companion object {
    /**
     * The section an object held this firmly belongs in, for the strengths that have one: everything from
     * [ReachabilityStrength.SOFT] down, which is everything the garbage collector clears without the app
     * doing anything. Null for the rest, whose objects are leaks and are named after what holds them.
     */
    fun ofOrNull(strength: ReachabilityStrength): LeakKind? =
      values().firstOrNull { it.strength == strength }

    /**
     * The one heading over every section that [isOnTheWayOut], since between them they are one answer: this
     * object shouldn't be in memory, and nothing you do will get rid of it any sooner.
     */
    const val ON_THE_WAY_OUT_TITLE = "On their way out"

    const val ON_THE_WAY_OUT_EXPLANATION =
      "Objects that shouldn't be in memory and are already leaving it, a section per way out. None of " +
        "these is a leak to fix — the garbage collector clears every one of them on its own, which is why " +
        "LeakCanary reports none of them. They are here so that a destroyed object still on the map has an " +
        "answer for why it is still there."
  }
}

/** One part of the leaks screen. See [LeakKind]. */
data class LeakSection(
  val kind: LeakKind,
  /** Largest first, by what the objects in them retain together. */
  val groups: List<LeakGroup>
) {

  val objectCount: Int get() = groups.sumOf { it.objects.size }
}

/**
 * One leak, and every object of the heap dump that is an instance of it.
 *
 * Grouped the way LeakCanary groups leaks, so that fifty leaked rows of one list read as one thing to fix
 * rather than as fifty: the app's own leaks by the references between what still holds the object and the
 * object itself, which is the part of the chain the leak is in, and the library ones by the known
 * reference they were recognized by.
 *
 * The sections whose objects are on their way out are grouped by the one reference the collector hasn't
 * cleared yet, for the same reason: one `Cleaner` that still has its referent is one row and a screen's
 * worth of views under it, rather than a row per view saying nothing about what they have in common.
 */
data class LeakGroup(
  /**
   * What makes two objects instances of the same leak, and so what tells one leak from another: two groups
   * can have the same [title] and never the same leak fingerprint.
   *
   * **The same string LeakCanary prints under this leak**, which is what makes a leak something to write
   * down: the same leak found in two heap dumps of the same app has the same one, however different the two
   * dumps are and whatever the addresses of the objects in them, and a report of a dump and this list of it
   * line up hash by hash. A SHA-1 of the suspect stretch of the chain for an app's own leak, of the pattern
   * for a library one — `shark.Leak.leakFingerprint`, computed by Shark's own code, see
   * `LeakFingerprint.kt`.
   */
  val leakFingerprint: String,
  /**
   * What the leak is: the reference it is, the pattern a library leak is known by, or the class of the
   * objects leaking for the one section that has no reference to name a group after.
   */
  val title: String,
  /**
   * The references the leak *is*, as `Foo.bar` each: from the highest one that could be what should have
   * been cleared, down to the one that points straight at what is stuck. A single reference for a leak on
   * its way out, that one being what still holds it rather than what should have let go.
   *
   * The stretch of the chain [leakFingerprint] hashes, which is what makes these objects one leak, and
   * the same for every object in the group. Both ends are worth reading — the first says what to stop
   * holding, the last says where on the chain to find what it left behind — and where they are one
   * reference, that reference is the faulty one and the chain marks it. See [PathReference.isFaulty].
   *
   * Empty for the leaks named some other way: a library leak is named by the pattern that recognized it,
   * and a leak nothing holds any more has no chain to read references off.
   */
  val suspectPath: List<String>,
  /**
   * What is known about the leak itself: the description of the library leak pattern that recognized it, or
   * that nothing holds these objects any more. Null for an app's own leak, which its references say.
   *
   * Not why an object is leaking — that is read off the object by an inspector, so it is
   * [LeakingObject.leakingReason] and differs between the objects of one group.
   */
  val subtitle: String?,
  /** Largest first. Never empty: a group is made by there being an object in it. */
  val objects: List<LeakingObject>
) {

  /** Bytes the objects of this leak retain together, which is what the leak is costing. */
  val retainedSize: Long get() = objects.sumOf { it.retainedSize }
}

/**
 * Hex, lowercase, the way every tool that prints a SHA-1 prints one — and the way
 * `shark.internal.createSHA1Hash` does, which is what [LeakGroup.leakFingerprint] has to agree with and can't
 * call, since it's internal to Shark.
 */
internal fun String.sha1Hex(): String =
  MessageDigest.getInstance("SHA-1").digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

/** One object that is stuck in memory. See [LeakGroup]. */
data class LeakingObject(
  val objectId: Long,
  /** Fully qualified class name, or array type. */
  val className: String,
  val kind: HeapObjectKind,
  /**
   * What tells it apart from the next object of its class — a string's content, a bitmap's size — for the
   * kinds Shark Dive recognizes, null for the rest.
   */
  val headline: String?,
  val retainedSize: Long,
  /** Number of objects retained, including this one. */
  val retainedCount: Int,
  /** How firmly it is held, which for a leak that has been collected already is unreachable. */
  val strength: ReachabilityStrength,
  /** Why this object is leaking, from the inspector that recognized it. */
  val leakingReason: String?,
  /** What LeakCanary's watcher recorded about it, for the objects an app handed over. Null for the rest. */
  val watcher: WatchedObject?
)
