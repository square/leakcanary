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
 * What Shark Dive draws is the path, which says more than a leak trace does — the strengths, the
 * dominators, the retained sizes of every step. A leak trace is the narrower artefact on purpose: it is what
 * LeakCanary prints, so it is what somebody receiving one can compare against a report they already have.
 */
internal fun List<PathStep>.toLeakTrace(gcRootType: LeakTrace.GcRootType): LeakTrace = LeakTrace(
  gcRootType = gcRootType,
  // A step of a path is an object and the reference that reached it; a leak trace reference is an object
  // and the reference out of it. So each pairs with the step below, and the last object has no pair.
  referencePath = dropLast(1).mapIndexed { index, step ->
    val reference = this[index + 1].reference!!
    LeakTraceReference(
      originObject = step.toLeakTraceObject(),
      referenceType = reference.locationType.toReferenceType(),
      owningClassName = reference.ownerClassName,
      referenceName = reference.name
    )
  },
  leakingObject = last().toLeakTraceObject()
)

private fun PathStep.toLeakTraceObject() = LeakTraceObject(
  type = when (kind) {
    HeapObjectKind.CLASS -> ObjectType.CLASS
    HeapObjectKind.INSTANCE -> ObjectType.INSTANCE
    HeapObjectKind.OBJECT_ARRAY, HeapObjectKind.PRIMITIVE_ARRAY -> ObjectType.ARRAY
  },
  className = className,
  labels = inspectorLabels.toSet(),
  verdict = when (leakStatus) {
    LeakStatus.EXPECTED -> Verdict.EXPECTED
    LeakStatus.UNKNOWN -> Verdict.UNKNOWN
    LeakStatus.STUCK -> Verdict.STUCK
  },
  verdictReason = leakStatusReason.orEmpty(),
  retainedHeapByteSize = null,
  retainedObjectCount = null
)

private fun ReferenceLocationType.toReferenceType() = when (this) {
  ReferenceLocationType.INSTANCE_FIELD -> ReferenceType.INSTANCE_FIELD
  ReferenceLocationType.STATIC_FIELD -> ReferenceType.STATIC_FIELD
  ReferenceLocationType.LOCAL -> ReferenceType.LOCAL
  ReferenceLocationType.ARRAY_ENTRY -> ReferenceType.ARRAY_ENTRY
}
