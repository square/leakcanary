package shark.dive

import shark.LeakTrace
import shark.LeakTraceObject
import shark.LeakTraceObject.Verdict
import shark.LeakTraceObject.ObjectType
import shark.LeakTraceReference
import shark.LeakTraceReference.ReferenceType
import shark.ReferenceLocationType

/**
 * The leak fingerprint LeakCanary prints under a leak, computed for a path Shark Dive found: a SHA-1 of
 * the stretch of the path between the last expected object and the first stuck one,
 * spelled by the class of each object and the name of the reference out of it.
 *
 * **Shark's own [LeakTrace.leakFingerprint] rather than a rule of the same shape.** A leak fingerprint is
 * only worth printing if it is the same string as the one in a LeakCanary report of the same leak, and the
 * way to be sure of that is to hand the path to the code that computes it — the rule for which references
 * count is subtle enough that writing it twice means finding out later that the two differ. So this builds
 * the [LeakTrace] the path amounts to and hands it back its own hash.
 *
 * The GC root it names is [LeakTrace.GcRootType.UNKNOWN] because the leak fingerprint doesn't include the
 * root, so hashing one costs a lookup that changes nothing. [RootPath.leakTrace], which is the same trace
 * built to be *read*, passes the root the path actually starts at.
 */
internal fun List<PathStep>.leakFingerprint(): String =
  toLeakTrace(LeakTrace.GcRootType.UNKNOWN).leakFingerprint

/**
 * The [LeakTrace] a path amounts to, which has two readers and so is not private: hashed into a leak
 * fingerprint here, and printed for a person by [RootPath.leakTrace].
 *
 * What Shark Dive draws is the path, which says more than a leak trace does — the strength and the
 * dominators of every step, and a retained size on each of them rather than on the last alone. A leak trace
 * is the narrower artefact on purpose: it is what LeakCanary prints, so it is what somebody receiving one
 * can compare against a report they already have.
 *
 * **Which is why every line LeakCanary puts in one is built here, and in its order.** A trace missing a line
 * is not read as a tool that renders less — it is read as a heap dump that says less, and the thing somebody
 * does with it is go looking for the fact in a report that has it. Measured against `shark-cli analyze` on
 * `shark/shark-android/src/test/resources/leak_asynctask_o.hprof`, which is the check to repeat after
 * changing this: identical fingerprint, identical path, identical labels and status reasons, and the only
 * remaining difference is the `Also retains leaking object …` labels — those name the *other* leaks an
 * analysis found under this one, which Shark Dive drops from its list instead of labelling, see
 * `foldedIntoWhatHoldsThem`.
 */
internal fun List<PathStep>.toLeakTrace(gcRootType: LeakTrace.GcRootType): LeakTrace = LeakTrace(
  gcRootType = gcRootType,
  // A step of a path is an object and the reference that reached it; a leak trace reference is an object
  // and the reference out of it. So each pairs with the step below, and the last object has no pair.
  referencePath = dropLast(1).mapIndexed { index, step ->
    val reference = this[index + 1].reference!!
    LeakTraceReference(
      originObject = step.toLeakTraceObject(libraryLeakOut = reference.libraryLeak),
      referenceType = reference.locationType.toReferenceType(),
      owningClassName = reference.ownerClassName,
      referenceName = reference.name
    )
  },
  leakingObject = last().toLeakTraceObject(libraryLeakOut = null, isLeakingObject = true)
)

private fun PathStep.toLeakTraceObject(
  /**
   * The library leak pattern matched by the reference *out* of this object, which a leak trace says as a
   * label on the referrer rather than on the reference it is about. Shark Dive keeps it on
   * [PathReference.libraryLeak], where the window draws it, so this is the one place the two spellings meet.
   */
  libraryLeakOut: LibraryLeakPattern?,
  /** Whether this is the object the path is about, which is the only one a leak trace credits a size to. */
  isLeakingObject: Boolean = false
) = LeakTraceObject(
  type = when (kind) {
    HeapObjectKind.CLASS -> ObjectType.CLASS
    HeapObjectKind.INSTANCE -> ObjectType.INSTANCE
    HeapObjectKind.OBJECT_ARRAY, HeapObjectKind.PRIMITIVE_ARRAY -> ObjectType.ARRAY
  },
  className = className,
  // The library leak label first and the inspectors' after, which is the order a leak trace prints them in
  // because that is the order they were added in: `RealLeakTracerFactory.inspectObjects` puts this one on
  // the reporter before running a single inspector over it.
  labels = (listOfNotNull(libraryLeakOut?.let { "Library leak match: ${it.pattern}" }) + inspectorLabels)
    .toSet(),
  verdict = when (leakStatus) {
    LeakStatus.EXPECTED -> Verdict.EXPECTED
    LeakStatus.UNKNOWN -> Verdict.UNKNOWN
    LeakStatus.STUCK -> Verdict.STUCK
  },
  verdictReason = leakStatusReason.orEmpty(),
  // Null for every step but the last, matching what LeakCanary credits: a retained size on each step is
  // what the path pane is for, and putting one on each here would be lines to explain away to whoever is
  // comparing this against a report. Null for an object folded into another one as well — [isTreeNode] is
  // false and both numbers are zero, which would print as a leaking object retaining nothing.
  retainedHeapByteSize = if (isLeakingObject && isTreeNode) retainedSize.asTraceSize() else null,
  retainedObjectCount = if (isLeakingObject && isTreeNode) retainedCount else null
)

/**
 * A leak trace holds a retained size in an `Int`, so this is where an object retaining more than 2 GB lands.
 * Capped rather than wrapped: a dump that big is a dump to read in the window anyway, and a negative number
 * of bytes in the artefact meant for comparing against a report is the one answer nobody can act on.
 */
private fun Long.asTraceSize(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun ReferenceLocationType.toReferenceType() = when (this) {
  ReferenceLocationType.INSTANCE_FIELD -> ReferenceType.INSTANCE_FIELD
  ReferenceLocationType.STATIC_FIELD -> ReferenceType.STATIC_FIELD
  ReferenceLocationType.LOCAL -> ReferenceType.LOCAL
  ReferenceLocationType.ARRAY_ENTRY -> ReferenceType.ARRAY_ENTRY
}
