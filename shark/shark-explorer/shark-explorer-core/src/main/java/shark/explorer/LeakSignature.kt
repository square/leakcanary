package shark.explorer

import shark.LeakTrace
import shark.LeakTraceObject
import shark.LeakTraceObject.LeakingStatus
import shark.LeakTraceObject.ObjectType
import shark.LeakTraceReference
import shark.LeakTraceReference.ReferenceType
import shark.ReferenceLocationType

/**
 * The signature LeakCanary prints under a leak, computed for a chain the explorer found: a SHA-1 of the
 * stretch of the chain between the last object still needed and the first one that shouldn't be there,
 * spelled by the class of each object and the name of the reference out of it.
 *
 * **Shark's own [LeakTrace.signature] rather than a rule of the same shape.** A signature is only worth
 * printing if it is the same string as the one in a LeakCanary report of the same leak, and the way to be
 * sure of that is to hand the chain to the code that computes it — the rule for which references count is
 * subtle enough that writing it twice means finding out later that the two differ. So this builds the
 * [LeakTrace] the chain amounts to and hands it back its own hash.
 *
 * That trace is built to be hashed and for nothing else: what the explorer draws is the chain, which says
 * more than a leak trace does. Which is why the GC root it names is [LeakTrace.GcRootType.UNKNOWN] — the
 * signature doesn't include the root, and the explorer names roots its own way, see
 * `HeapDominatorTreemap.rootPathTo`.
 */
internal fun List<PathStep>.leakSignature(): String = toLeakTrace().signature

private fun List<PathStep>.toLeakTrace(): LeakTrace = LeakTrace(
  gcRootType = LeakTrace.GcRootType.UNKNOWN,
  // A step of a chain is an object and the reference that reached it; a leak trace reference is an object
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
  leakingStatus = when (leakStatus) {
    LeakStatus.NOT_LEAKING -> LeakingStatus.NOT_LEAKING
    LeakStatus.UNKNOWN -> LeakingStatus.UNKNOWN
    LeakStatus.LEAKING -> LeakingStatus.LEAKING
  },
  leakingStatusReason = leakStatusReason.orEmpty(),
  retainedHeapByteSize = null,
  retainedObjectCount = null
)

private fun ReferenceLocationType.toReferenceType() = when (this) {
  ReferenceLocationType.INSTANCE_FIELD -> ReferenceType.INSTANCE_FIELD
  ReferenceLocationType.STATIC_FIELD -> ReferenceType.STATIC_FIELD
  ReferenceLocationType.LOCAL -> ReferenceType.LOCAL
  ReferenceLocationType.ARRAY_ENTRY -> ReferenceType.ARRAY_ENTRY
}
