package leakcanary.internal

import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HydratedInstance

internal class KeyedWeakReferenceMirror(
  val key: ObjectReference,
    // The className field does not exist in pre 1.0 heap dumps.
  val name: ObjectReference?,
    // The className field does not exist in pre 2.0 heap dumps.
  val className: ObjectReference?,
  val watchDurationMillis: Long,
  val referent: ObjectReference
) {

  val hasReferent = referent.value != 0L

  companion object {
    fun fromInstance(
      weakRef: HydratedInstance,
      heapDumpUptimeMillis: Long
    ): KeyedWeakReferenceMirror {
      // The watchUptimeMillis field does not exist in pre 2.0 heap dumps.
      val watchUptimeMillis = weakRef.fieldValueOrNull<LongValue>(
          "watchUptimeMillis"
      )
          ?.value ?: 0
      return KeyedWeakReferenceMirror(
          key = weakRef.fieldValue("key"),
          name = weakRef.fieldValueOrNull("name"),
          className = weakRef.fieldValueOrNull("className"),
          watchDurationMillis = heapDumpUptimeMillis - watchUptimeMillis,
          referent = weakRef.fieldValue("referent")
      )
    }
  }
}

