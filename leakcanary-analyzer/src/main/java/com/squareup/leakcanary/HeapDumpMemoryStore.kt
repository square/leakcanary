package com.squareup.leakcanary

object HeapDumpMemoryStore {

  @JvmStatic var retainedKeysForHeapDump: Array<String>? = null
    private set
  @JvmStatic var heapDumpUptimeMillis: Long = 0

  @JvmStatic fun setRetainedKeysForHeapDump(retainedKeys: Set<String>) {
    retainedKeysForHeapDump = retainedKeys.toTypedArray()
  }

}