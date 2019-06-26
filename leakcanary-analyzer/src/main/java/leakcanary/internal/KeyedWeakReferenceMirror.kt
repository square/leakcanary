package leakcanary.internal

import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofParser
import leakcanary.HydratedInstance

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
      parser: HprofParser,
      weakRef: HydratedInstance,
      // Null for pre 2.0 alpha 3 heap dumps
      heapDumpUptimeMillis: Long?
    ): KeyedWeakReferenceMirror {

      val watchDurationMillis = if (heapDumpUptimeMillis != null)
        heapDumpUptimeMillis - weakRef.fieldValue<LongValue>("watchUptimeMillis").value
      else 0L

      val retainedDurationMillis = if (heapDumpUptimeMillis != null) {
        val retainedUptimeMillis = weakRef.fieldValue<LongValue>("retainedUptimeMillis")
            .value
        if (retainedUptimeMillis == -1L) -1L else heapDumpUptimeMillis - retainedUptimeMillis
      } else null

      val key = weakRef.fieldValue<ObjectReference>("key")
      val keyString = parser.retrieveString(key)

      val name = weakRef.fieldValueOrNull<ObjectReference>("name")
      val nameString = if (name != null) parser.retrieveString(name) else UNKNOWN_LEGACY
      val className = weakRef.fieldValueOrNull<ObjectReference>("className")
      val classNameString =
        if (className != null) parser.retrieveString(className) else UNKNOWN_LEGACY

      return KeyedWeakReferenceMirror(
          watchDurationMillis = watchDurationMillis,
          retainedDurationMillis = retainedDurationMillis,
          referent = weakRef.fieldValue("referent"),
          key = keyString,
          name = nameString,
          className = classNameString
      )
    }
  }
}

