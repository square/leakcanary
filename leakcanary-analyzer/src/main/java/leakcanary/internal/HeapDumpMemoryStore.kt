package leakcanary.internal

object HeapDumpMemoryStore {

  @Volatile
  @JvmStatic var retainedKeysForHeapDump: Array<String>? = null
    private set

  @Volatile
  @JvmStatic var heapDumpUptimeMillis: Long = 0

  @JvmStatic fun setRetainedKeysForHeapDump(retainedKeys: Set<String>) {
    retainedKeysForHeapDump = retainedKeys.toTypedArray()
  }

}