package shark.explorer

/**
 * A stretch of a [RootPath] that didn't have to run the way it does: the steps between two objects that
 * both hold the object the chain leads to no matter which way round is taken.
 *
 * Every path from a GC root goes through every dominator of the object, so a step that dominates it is one
 * the chain has no choice about. A run of steps between two of those is the opposite: if the chain had to
 * run through them, they would dominate the object as well and be marked. So a detour is exactly where the
 * question "held how else?" has an answer, and [HeapDominatorTreemap.independentPathsBetween] is the answer
 * — asked of the two ends rather than of the whole chain, which is what keeps it to the part in doubt.
 */
data class RootPathDetour(
  /** Index into [RootPath.steps] of the first step that could have been another object. */
  val fromIndex: Int,
  /**
   * And of the step below the last of them, which is the next step the chain has no choice about: the next
   * dominator down, or the object the chain leads to.
   */
  val toIndex: Int,
  /**
   * The object every way of running this stretch starts below, or null for one running off the top of the
   * chain, where what holds it is a GC root rather than an object.
   */
  val fromObjectId: Long?,
  /** The object every way of running it arrives at, which is the step at [toIndex]. */
  val toObjectId: Long
)

/**
 * The stretches of this chain that could have run some other way, top to bottom.
 *
 * The steps that dominate the object cut the chain into these: each detour is a run of steps that don't,
 * plus the step below it that does. A chain whose every step dominates the object has none of them — there
 * is one way to hold it and the chain is it.
 */
fun RootPath.detours(): List<RootPathDetour> {
  if (steps.isEmpty()) {
    return emptyList()
  }
  // The head, every dominator, and the object itself: the points the chain is pinned to.
  val pinned = listOf(HEAD_INDEX) +
    steps.indices.filter { steps[it].isDominator } +
    listOf(steps.lastIndex)
  return pinned.zipWithNext().mapNotNull { (above, below) ->
    // Nothing in between, so nothing that could have been something else. Also the last pair of a chain
    // whose object is itself the step below the last dominator.
    if (below <= above + 1) {
      null
    } else {
      RootPathDetour(
        fromIndex = above + 1,
        toIndex = below,
        fromObjectId = if (above == HEAD_INDEX) null else steps[above].step.objectId,
        toObjectId = steps[below].step.objectId
      )
    }
  }
}

/**
 * The ways one stretch of this chain runs: the way the chain itself takes first, then the ones [found] adds.
 *
 * The chain's own way first because it is the one already on screen, and the shortest: the search that
 * found the others walks breadth first, so anything it hands back is at least as long. Its own way is
 * dropped from [found] rather than shown twice — the search reports it too, being one of the ways.
 */
fun RootPath.waysOf(
  detour: RootPathDetour,
  found: IndependentPaths
): List<RootPathWay> {
  val ownSteps = steps.subList(detour.fromIndex, detour.toIndex + 1)
  val isHead = detour.fromObjectId == null
  val own = RootPathWay(
    // Only a stretch off the top of the chain has a GC root above it to name: the rest start at an object
    // the chain shows.
    gcRootLabel = if (isHead) gcRootLabel else null,
    steps = ownSteps
  )
  val ownObjectIds = ownSteps.map { it.step.objectId }
  val alternatives = found.paths
    .filter { it.steps.map { step -> step.objectId } != ownObjectIds }
    .map { path ->
      RootPathWay(
        gcRootLabel = path.gcRootLabel,
        // Only the step this stretch arrives at can dominate the object: the rest are steps the chain
        // could have gone round, which is what made this a detour.
        steps = path.steps.mapIndexed { index, step ->
          RootPathStep(step, isDominator = index == path.steps.lastIndex && steps[detour.toIndex].isDominator)
        }
      )
    }
  return listOf(own) + alternatives
}

/** One way a stretch of a chain runs, from the step below where it starts down to where it arrives. */
data class RootPathWay(
  /** Which GC root it starts at, for a stretch off the top of a chain. Null below an object. */
  val gcRootLabel: String?,
  val steps: List<RootPathStep>
)

/**
 * This chain as it is drawn, with [wayOf]'s way taken for each of [detours].
 *
 * Substituted here rather than by the drawing, so that what a reader is looking at is one chain of steps
 * however many of its stretches they have switched: a chain drawn from the original steps plus a set of
 * replacements is a chain whose steps and whose lines have to be worked out separately, and they have to
 * agree.
 */
fun RootPath.drawnWith(
  detours: List<RootPathDetour>,
  /** The way to draw one stretch, or null to leave the chain's own steps where they are. */
  wayOf: (RootPathDetour) -> RootPathWay?
): DrawnRootPath {
  val detourByFromIndex = detours.associateBy { it.fromIndex }
  val drawn = mutableListOf<RootPathStep>()
  val detourByRow = mutableMapOf<Int, RootPathDetour>()
  var gcRootLabel = this.gcRootLabel
  var index = 0
  while (index < steps.size) {
    val detour = detourByFromIndex[index]
    if (detour == null) {
      drawn += steps[index]
      index++
      continue
    }
    // Under the step above the stretch, which is what it is a stretch below, and [HEAD_INDEX] when that is
    // the head of the chain rather than a step of it.
    detourByRow[drawn.lastIndex] = detour
    val way = wayOf(detour)
    if (way == null) {
      drawn += steps.subList(detour.fromIndex, detour.toIndex + 1)
    } else {
      drawn += way.steps
      if (detour.fromObjectId == null) {
        gcRootLabel = way.gcRootLabel
      }
    }
    index = detour.toIndex + 1
  }
  return DrawnRootPath(
    path = RootPath(gcRootLabel = gcRootLabel, steps = drawn),
    detourByRow = detourByRow
  )
}

/** A chain with the way each of its stretches runs decided, ready to draw. See [drawnWith]. */
data class DrawnRootPath(
  val path: RootPath,
  /**
   * The stretch that hangs under each step, by index into the drawn chain's steps. [HEAD_INDEX] for one
   * hanging under the head of the chain.
   */
  val detourByRow: Map<Int, RootPathDetour>
)

/** Above the first step of a chain, which is the head naming the GC root rather than an object. */
const val HEAD_INDEX = -1
