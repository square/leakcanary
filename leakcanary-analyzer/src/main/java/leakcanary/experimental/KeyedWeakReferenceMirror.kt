package leakcanary.experimental

import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HydratedInstance

internal class KeyedWeakReferenceMirror(
  val key: ObjectReference,
  val name: ObjectReference,
  val className: ObjectReference,
  val watchDurationMillis: Long,
  val referent: ObjectReference
) {

  val hasReferent = referent.value != 0L

  companion object {
    fun fromInstance(
      weakRef: HydratedInstance,
      heapDumpUptimeMillis: Long
    ): KeyedWeakReferenceMirror {
      return KeyedWeakReferenceMirror(
          key = weakRef.fieldValue("key"),
          name = weakRef.fieldValue("name"),
          className = weakRef.fieldValue("className"),
          watchDurationMillis = heapDumpUptimeMillis - weakRef.fieldValue<LongValue>(
              "watchUptimeMillis"
          ).value,
          referent = weakRef.fieldValue("referent")
      )
    }

  }
}

