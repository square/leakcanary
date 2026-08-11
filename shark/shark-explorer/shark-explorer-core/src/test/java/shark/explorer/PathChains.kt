package shark.explorer

/**
 * Chains built by hand, for the tests of what a chain is cut into and drawn as.
 *
 * Nothing about [RootPath.stepsBelow], [detours] or [drawnWith] reads a heap dump: they work off which steps
 * dominate the object, and saying that in a list is shorter and clearer than building a dump that produces
 * it. The tests of the search that fills these in are in [HeapExplorerTest].
 */

/** A chain of objects by id, each of them either a dominator of the last one or only on the way to it. */
internal fun chain(
  vararg steps: Pair<Long, Boolean>,
  gcRootLabel: String? = A_GC_ROOT
): RootPath = RootPath(
  gcRootLabel = gcRootLabel,
  steps = steps.map { (objectId, isDominator) -> RootPathStep(pathStep(objectId), isDominator) }
)

internal fun pathStep(objectId: Long): PathStep = PathStep(
  objectId = objectId,
  className = "com.example.Step$objectId",
  kind = HeapObjectKind.INSTANCE,
  headline = null,
  strength = ReachabilityStrength.STRONG,
  retainedSize = 0L,
  retainedCount = 0,
  inspectorLabels = emptyList(),
  leakStatus = LeakStatus.UNKNOWN,
  leakStatusReason = null,
  reference = null,
  isInspectable = true
)

internal fun List<RootPathStep>.objectIds(): List<Long> = map { it.step.objectId }

internal const val DOMINATES = true
internal const val ON_THE_WAY = false

internal const val A_GC_ROOT = "GC root: JNI global reference"
