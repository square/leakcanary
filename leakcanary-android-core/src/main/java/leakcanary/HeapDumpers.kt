package leakcanary

import leakcanary.internal.withManagedHeapDumpDirectory
import leakcanary.internal.withNotification
import leakcanary.internal.withResourceIdNames
import leakcanary.internal.withToast

fun leakCanaryHeapDumper(
  /**
   * The core [HeapDumper] which will be wrapped by delegates.
   */
  coreHeapDumper: HeapDumper = AndroidDebugHeapDumper,
  /**
   * Wraps [HeapDumper.dumpHeap] to show cute canary Toast while the heap is being dumped.
   */
  toastOnDump: Boolean = true,
  /**
   * Wraps [HeapDumper.dumpHeap] to show an Android notification that says "Dumping Heap" while the
   * heap is being dumped.
   */
  notificationOnDump: Boolean = true
): HeapDumper {
  var heapDumper = coreHeapDumper.withResourceIdNames()
  if (notificationOnDump) {
    heapDumper = heapDumper.withNotification()
  }
  if (toastOnDump) {
    heapDumper = heapDumper.withToast()
  }
  heapDumper = heapDumper.withManagedHeapDumpDirectory()
  return heapDumper
}
