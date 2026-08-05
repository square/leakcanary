package shark.explorer

import java.security.MessageDigest

/**
 * Every object of a heap dump that shouldn't be there, gathered into the leaks they are instances of. See
 * [HeapDominatorTreemap.findLeaks].
 *
 * The same answer LeakCanary's analysis gives, computed here instead so that every row keeps the object id
 * it came from: a leak trace names classes, and a screen you can click through has to name objects. Which
 * is also why there is no leak trace here — the chain the object explorer already draws is one, so a leak
 * is a list of objects to open and the explorer does the rest.
 */
data class HeapLeaks(
  /** In [LeakKind] order, all three of them, empty ones included: an empty section is an answer. */
  val sections: List<LeakSection>
) {

  /** How many leaking objects there are, which is what the button leading here says. */
  val objectCount: Int get() = sections.sumOf { it.objectCount }

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
 * Which of the three kinds of thing a leak is, which is what splits the screen into three.
 *
 * The split is what makes the list actionable: the app's own leaks are the ones to go and fix, the library
 * ones are somebody else's and are mostly there so they don't get mistaken for the app's, and the
 * unreachable objects are already gone and are here because their absence would read as nothing found.
 */
enum class LeakKind(
  val title: String,
  /** What being in this section means, since none of the three titles says it on its own. */
  val explanation: String
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

  UNREACHABLE(
    "Unreachable",
    "Objects that were meant to be gone and are: nothing reaches them any more, and the next garbage " +
      "collection would take them. Listed so that a heap dump whose leaks have all been collected doesn't " +
      "read as a heap dump nothing looked at."
  )
}

/** One of the three parts of the leaks screen. See [LeakKind]. */
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
 */
data class LeakGroup(
  /**
   * What makes two objects instances of the same leak, and so what tells one leak from another: two groups
   * can have the same [title] and never the same signature.
   *
   * **The same string LeakCanary prints under this leak**, which is what makes a leak something to write
   * down: the same leak found in two heap dumps of the same app has the same one, however different the two
   * dumps are and whatever the addresses of the objects in them, and a report of a dump and this list of it
   * line up hash by hash. A SHA-1 of the suspect stretch of the chain for an app's own leak, of the pattern
   * for a library one — `shark.Leak.signature`, computed by Shark's own code, see `LeakSignature.kt`.
   */
  val signature: String,
  /** What the leak is: the class of the objects leaking, or the reference a library leak is known by. */
  val title: String,
  /**
   * The references the leak *is*, as `Foo.bar` each: from the one that shouldn't be holding any more down
   * to the one that points straight at what leaked.
   *
   * The stretch of the chain [signature] hashes, which is what makes these objects one leak, and the same
   * for every object in the group. Both ends are worth reading — the first says what to stop holding, the
   * last says where on the chain to find what it left behind — and they are the same reference for a leak
   * held one step below something still needed, which is most leaks.
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
 * `shark.internal.createSHA1Hash` does, which is what [LeakGroup.signature] has to agree with and can't
 * call, since it's internal to Shark.
 */
internal fun String.sha1Hex(): String =
  MessageDigest.getInstance("SHA-1").digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

/** One object that shouldn't be in memory. See [LeakGroup]. */
data class LeakingObject(
  val objectId: Long,
  /** Fully qualified class name, or array type. */
  val className: String,
  val kind: HeapObjectKind,
  /**
   * What tells it apart from the next object of its class — a string's content, a bitmap's size — for the
   * kinds the explorer recognizes, null for the rest.
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
