package shark.internal

import shark.HeapObject.HeapInstance
import shark.ValueHolder
import shark.ValueHolder.ReferenceHolder

internal class KeyedWeakReferenceMirror(
  val referent: ReferenceHolder,
  val key: String,
    // The name field does not exist in pre 1.0 heap dumps.
  val description: String,
    // null in pre 2.0 alpha 3 heap dumps
  val watchDurationMillis: Long?,
    // null in pre 2.0 alpha 3 heap dumps, -1 if the instance is not retained.
  val retainedDurationMillis: Long?
) {

  val hasReferent = referent.value != ValueHolder.NULL_REFERENCE

  val isRetained = retainedDurationMillis == null || retainedDurationMillis != -1L

  companion object {

    private const val UNKNOWN_LEGACY = "Unknown (legacy)"

    fun fromInstance(
      weakRef: HeapInstance,
        // Null for pre 2.0 alpha 3 heap dumps
      heapDumpUptimeMillis: Long?
    ): KeyedWeakReferenceMirror {

      val keyWeakRefClassName = weakRef.instanceClassName
      val watchDurationMillis = if (heapDumpUptimeMillis != null) {
        heapDumpUptimeMillis - weakRef[keyWeakRefClassName, "watchUptimeMillis"]!!.value.asLong!!
      } else {
        null
      }

      val retainedDurationMillis = if (heapDumpUptimeMillis != null) {
        val retainedUptimeMillis =
          weakRef[keyWeakRefClassName, "retainedUptimeMillis"]!!.value.asLong!!
        if (retainedUptimeMillis == -1L) -1L else heapDumpUptimeMillis - retainedUptimeMillis
      } else {
        null
      }

      val keyString = weakRef[keyWeakRefClassName, "key"]!!.value.readAsJavaString()!!

      // Changed from name to description after 2.0
      val description = (weakRef[keyWeakRefClassName, "description"]
          ?: weakRef[keyWeakRefClassName, "name"])?.value?.readAsJavaString() ?: UNKNOWN_LEGACY
      return KeyedWeakReferenceMirror(
          watchDurationMillis = watchDurationMillis,
          retainedDurationMillis = retainedDurationMillis,
          referent = weakRef["java.lang.ref.Reference", "referent"]!!.value.holder as ReferenceHolder,
          key = keyString,
          description = description
      )
    }
  }
}

