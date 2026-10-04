package shark.dive

import shark.LeakTrace

/**
 * The shortest way a GC root reaches one object, with the objects that dominate it marked. See
 * [HeapDominatorTreemap.rootPathTo].
 *
 * What a treemap can't say on its own: a rectangle says which object the tree attributes these bytes to,
 * and this says which of the heap dump's own references had to be followed to get to them. Shortest
 * counted in steps, so it's the plainest way the object is held rather than one of the ways round —
 * [IndependentPaths] is what spells out the others.
 */
data class RootPath(
  /**
   * Which kind of GC root the path starts at, or that the object is uncollected garbage. Null when
   * nothing the tree was built from reaches it, which is when [steps] is empty.
   */
  val gcRootLabel: String?,
  /** From the GC rooted object down to the object itself, which is the last step. */
  val steps: List<RootPathStep>,
  /**
   * The same root as [gcRootLabel], in Shark's own vocabulary, for [leakTrace] to name it with.
   *
   * Two spellings of one fact, which the rest of this app does not allow — and the exception is earned by
   * who reads each: [gcRootLabel] is this app's words on a screen and in its own answers, and this is what
   * goes into a `LeakTrace`, where the words have to be the ones LeakCanary prints or the trace is not the
   * same artefact. Both are derived in `HeapDominatorTreemap.rootPathAlong` from the one `GcRoot`, so
   * neither can drift from the other.
   *
   * Null exactly when [gcRootLabel] is, and for uncollected garbage, which no GC root reaches.
   */
  val gcRootType: LeakTrace.GcRootType? = null
) {
  companion object {
    /** No path: nothing the tree was built from reaches the object. */
    val NONE = RootPath(gcRootLabel = null, steps = emptyList())
  }
}

/**
 * The part of this path a map rooted at [rootNodeId] is showing: from the object drawn as one of that
 * root's own rectangles down to the object the path leads to.
 *
 * Which is all a path glanced at while the pointer moves has to say. The rectangle under the pointer is
 * somewhere inside one of the blocks the map is divided into, and which block that is answers what holds it
 * *here*; the steps above are how that block itself is held, which is what going there would say instead.
 *
 * The first dominator below the root, because every path from a GC root to the object goes through every one
 * of its dominators: the dominator nearest the root is the rectangle the map draws it inside of. A step only
 * on the way is not one of those, so it is cut along with the rest. Just the object itself when nothing below
 * the root dominates it, which is what pointing at one of the root's own rectangles is.
 *
 * The whole path when [rootNodeId] is nowhere on it, which is a map rooted at a pile of objects: a pile is
 * no object of the heap dump, so it says nothing about where the path reaches the screen.
 */
fun RootPath.stepsBelow(rootNodeId: Long): List<RootPathStep> {
  if (steps.isEmpty()) {
    return steps
  }
  val rootIndex = steps.indexOfFirst { it.step.objectId == rootNodeId }
  if (rootIndex == -1 && rootNodeId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
    return steps
  }
  val fromIndex = (rootIndex + 1 until steps.size).firstOrNull { steps[it].isDominator }
    ?: steps.lastIndex
  return steps.subList(fromIndex, steps.size)
}

/**
 * The one reference this path is the leak *of*, and null for a path that isn't a solved leak.
 *
 * Which is what a path is read for: the objects on it say what is still in memory, and this says what to
 * go and change. A reader who has set the two verdicts either side of one reference has finished — the heap
 * dump has no more to add — so this being non-null is the same fact as the investigation being over.
 *
 * At most one, because [PathReference.isFaulty] is set for the single crossing from expected to stuck and
 * for nothing else, so there is no need for a caller to decide between two of them. See
 * [faultyReferenceIndexOrNull] for the rule and for the three ways a path has none.
 */
fun RootPath.faultyReference(): PathReference? =
  steps.firstNotNullOfOrNull { step -> step.step.reference?.takeIf { it.isFaulty } }

/**
 * Which references this path says the leak could be — [faultyReference] and nothing else once the verdicts
 * narrow to one, and the whole stretch between them while they haven't.
 *
 * **Counted in references and not in objects**, which is the thing to get right about a narrowed path: one
 * object with no verdict between the two ends leaves *two* candidates, the reference into it and the
 * reference out of it, and what decides between them is that object's own verdict. So a reader with a
 * stretch left has one question, and it is about an object rather than about a reference: is this object's
 * work done?
 *
 * Empty for a path with nothing stuck on it. The same words the leaks screen names a leak with — see
 * [suspectReferenceLabels].
 */
fun RootPath.suspectReferences(): List<String> = steps.map { it.step }.suspectReferenceLabels()

/**
 * Whether this path names the one reference the leak is, which is the whole of what solving a leak is.
 *
 * The same fact as [faultyReference] being non-null, named after what it means to somebody working: there
 * is nothing left for verdicts to narrow, and what remains is reading the code that assigns that field.
 */
fun RootPath.isLeakSolved(): Boolean = faultyReference() != null

/**
 * How many references on this path are still candidates for being the faulty one. See [suspectReferences].
 *
 * 1 for a solved leak, 0 for a path with nothing stuck on it — which are opposite ends and not neighbours,
 * so read [isLeakSolved] rather than comparing this to 1.
 */
fun RootPath.suspectReferenceCount(): Int = suspectReferences().size

/**
 * Every reference on this path, which is what [leakSolvingProgressRatio] measures the candidates against.
 *
 * One less than the steps for a path a GC root starts, whose first object no field points at — and the
 * filter rather than that subtraction because a step below the first can be missing its reference too, see
 * `HeapDominatorTreemap.stepTo`.
 */
fun RootPath.referenceCount(): Int = steps.count { it.step.reference != null }

/**
 * How far the verdicts set so far have narrowed the search, from 0 to just under 1.
 *
 * `1 - candidates / references`: every reference on the path is a candidate until a verdict rules it out,
 * so this is the share of them the verdicts have eliminated. What it is for is being able to tell a verdict
 * that moved the investigation from one that didn't, without re-reading the whole path.
 *
 * **`Ratio` is in the name because 0 to 1 is not the only way to spell a share**, and the other one is a
 * percentage: a reader who takes 0.2 for 0.2% has read this as a fifth of a percent of the way through. The
 * suffix is [Prometheus' convention](https://prometheus.io/docs/practices/naming/) for exactly this, where a
 * `_ratio` is 0 to 1 and anything 0 to 100 is named otherwise, and every spelling of this number on every
 * surface carries it. There was no suffix, and the function rounding it for an agent multiplied by a
 * constant called `PROGRESS_SCALE` and divided by it again, which is two decimal places and reads as a
 * conversion to percent — it was read that way the first time somebody looked.
 *
 * **It does not reach 1, and that is not an off-by-one.** A solved leak still has one candidate — the faulty
 * reference itself — so a solved path of twenty references reads 0.95. The number that says an investigation
 * is over is [isLeakSolved], and leaving this one short of 1 is what keeps the two from being read as the
 * same claim.
 *
 * 0 for a path with nothing stuck on it, where no reference has been ruled out because the search has not
 * begun: [suspectReferences] is empty there, which through the formula alone would read as 1.
 */
fun RootPath.leakSolvingProgressRatio(): Double {
  val candidates = suspectReferenceCount()
  val references = referenceCount()
  if (candidates == 0 || references == 0) {
    return 0.0
  }
  return 1.0 - candidates.toDouble() / references
}

/**
 * This path as the leak trace LeakCanary prints, which is the one rendering of it meant for a person.
 *
 * **The only way a leak trace is allowed to reach a human from this app**, and the reason it is here rather
 * than assembled by whoever is doing the telling: a trace built by concatenating the strings of an answer is
 * one whose steps, verdicts and underline are a retelling, and a retelling of a leak trace that drops a step
 * or moves the underline is indistinguishable from a real one to the person reading it. So an agent and the
 * window hand back the same characters, produced by `shark.LeakTrace` itself from this path.
 *
 * Null for a path with no steps, which is nothing to render.
 */
fun RootPath.leakTrace(): LeakTrace? {
  if (steps.isEmpty()) {
    return null
  }
  return steps.map { it.step }.toLeakTrace(gcRootType ?: LeakTrace.GcRootType.UNKNOWN)
}

/**
 * The part of this path below [objectId], or null when no step of it is that object.
 *
 * What the path to the rectangle under the pointer has to add to the path already on screen: the object
 * the window is describing is on both of them, so the steps below it are the whole of the difference, and
 * they read as the path running on rather than as a second path of their own.
 */
fun RootPath.stepsAfter(objectId: Long): List<RootPathStep>? {
  val index = steps.indexOfFirst { it.step.objectId == objectId }
  return if (index == -1) null else steps.subList(index + 1, steps.size)
}

/**
 * One object along a [RootPath].
 *
 * [isDominator] is what makes the path more than a list of holders: every path from a GC root to the
 * object goes through each of its dominators, so a marked step is one that releasing would free the
 * object, and the rest are only on the way to it.
 */
data class RootPathStep(
  val step: PathStep,
  val isDominator: Boolean
)
