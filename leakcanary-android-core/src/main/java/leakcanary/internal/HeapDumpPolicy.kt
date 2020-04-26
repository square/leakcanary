package leakcanary.internal

import leakcanary.LeakCanary
import leakcanary.internal.HeapDumpPolicy.HeapDumpStatus.DISABLED_CONFIG_CHANGED
import leakcanary.internal.HeapDumpPolicy.HeapDumpStatus.DISABLED_DEBUGGER_ATTACHED
import leakcanary.internal.HeapDumpPolicy.HeapDumpStatus.ENABLED

object HeapDumpPolicy {
  enum class HeapDumpStatus {
    ENABLED,
    DISABLED_DEBUGGER_ATTACHED,
    DISABLED_CONFIG_CHANGED
  }

  fun getHeapDumpStatus(): HeapDumpStatus {
    val config = LeakCanary.config

    if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
      return DISABLED_DEBUGGER_ATTACHED
    }

    if (!config.dumpHeap) {
      return DISABLED_CONFIG_CHANGED
    }

    return ENABLED
  }
}