package shark.explorer

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
   * What makes two objects instances of the same leak, and so what tells one leak from another: two
   * groups can have the same [title] and never the same id.
   */
  val id: String,
  /** What the leak is: the class of the objects leaking, or the reference a library leak is known by. */
  val title: String,
  /** Why they are leaking, or what is known about the library leak. Null when there is nothing to add. */
  val subtitle: String?,
  /** Largest first. Never empty: a group is made by there being an object in it. */
  val objects: List<LeakingObject>
) {

  /** Bytes the objects of this leak retain together, which is what the leak is costing. */
  val retainedSize: Long get() = objects.sumOf { it.retainedSize }
}

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
