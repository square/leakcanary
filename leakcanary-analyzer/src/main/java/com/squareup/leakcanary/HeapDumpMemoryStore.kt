package com.squareup.leakcanary

object HeapDumpMemoryStore {

  @JvmStatic var retainedKeysForHeapDump: Array<String>? = null
  @JvmStatic var heapDumpUptimeMillis: Long = 0
}