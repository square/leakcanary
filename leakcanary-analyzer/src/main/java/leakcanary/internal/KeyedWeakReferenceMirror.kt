package leakcanary.internal

import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HydratedInstance

internal class KeyedWeakReferenceMirror(
  val key: ObjectReference,
  val name: ObjectReference,
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
      val watchUptimeMillis = weakRef.fieldValueOrNull<LongValue>(
          "watchUptimeMillis"
      )
          ?.value ?: 0
      return KeyedWeakReferenceMirror(
          key = weakRef.fieldValue("key"),
          name = weakRef.fieldValue("name"),
          className = weakRef.fieldValueOrNull("className"),
          watchDurationMillis = heapDumpUptimeMillis - watchUptimeMillis,
          referent = weakRef.fieldValue("referent")
      )
    }
  }
}

