package leakcanary.internal

import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.HeapValue.ObjectReference

internal class KeyedWeakReferenceMirror(
  val referent: ObjectReference,
  val key: String,
    // The name field does not exist in pre 1.0 heap dumps.
  val name: String,
  // 0 in pre 2.0 alpha 3 heap dumps
  val watchDurationMillis: Long,
    // The className field does not exist in pre 2.0 heap dumps.
  val className: String,
    // null in pre 2.0 alpha 3 heap dumps, -1 if the instance is not retained.
  val retainedDurationMillis: Long?
) {

  val hasReferent = referent.value != 0L

  val isRetained = retainedDurationMillis == null || retainedDurationMillis != -1L

  companion object {

    private const val UNKNOWN_LEGACY = "Unknown (legacy)"

    fun fromInstance(
      weakRef: GraphInstanceRecord,
      // Null for pre 2.0 alpha 3 heap dumps
      heapDumpUptimeMillis: Long?
    ): KeyedWeakReferenceMirror {

      val keyWeakRefClassName = weakRef.className
      val watchDurationMillis = if (heapDumpUptimeMillis != null)
        heapDumpUptimeMillis - weakRef[keyWeakRefClassName, "watchUptimeMillis"]!!.value.asLong!!
      else 0L

      val retainedDurationMillis = if (heapDumpUptimeMillis != null) {
        val retainedUptimeMillis = weakRef[keyWeakRefClassName, "retainedUptimeMillis"]!!.value.asLong!!
        if (retainedUptimeMillis == -1L) -1L else heapDumpUptimeMillis - retainedUptimeMillis
      } else null

      val keyString = weakRef[keyWeakRefClassName, "key"]!!.value.readAsJavaString()!!

      val name = weakRef[keyWeakRefClassName, "name"]?.value?.readAsJavaString() ?: UNKNOWN_LEGACY
      val className = weakRef[keyWeakRefClassName, "className"]?.value?.readAsJavaString() ?: UNKNOWN_LEGACY
      return KeyedWeakReferenceMirror(
          watchDurationMillis = watchDurationMillis,
          retainedDurationMillis = retainedDurationMillis,
          referent = weakRef["java.lang.ref.Reference", "referent"]!!.value.actual as ObjectReference,
          key = keyString,
          name = name,
          className = className
      )
    }
  }
}

