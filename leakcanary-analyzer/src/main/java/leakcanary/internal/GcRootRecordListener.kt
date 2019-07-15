package leakcanary.internal

import leakcanary.GcRoot
import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.JniGlobal
import leakcanary.GcRoot.JniLocal
import leakcanary.GcRoot.JniMonitor
import leakcanary.GcRoot.MonitorUsed
import leakcanary.GcRoot.NativeStack
import leakcanary.GcRoot.ReferenceCleanup
import leakcanary.GcRoot.StickyClass
import leakcanary.GcRoot.ThreadBlock
import leakcanary.GcRoot.ThreadObject
import leakcanary.HprofPushRecordsParser.OnRecordListener
import leakcanary.Record
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import kotlin.reflect.KClass

internal class GcRootRecordListener : OnRecordListener {

  val gcRoots = mutableListOf<GcRoot>()

  override fun recordTypes(): Set<KClass<out Record>> = setOf(GcRootRecord::class)

  override fun onTypeSizesAvailable(typeSizes: Map<Int, Int>) {
  }

  override fun onRecord(
    position: Long,
    record: Record
  ) {
    when (record) {
      is GcRootRecord -> {
        // TODO Ignoring VmInternal because we've got 150K of it, but is this the right thing
        // to do? What's VmInternal exactly? History does not go further than
        // https://android.googlesource.com/platform/dalvik2/+/refs/heads/master/hit/src/com/android/hit/HprofParser.java#77
        // We should log to figure out what objects VmInternal points to.
        when (record.gcRoot) {
          // ThreadObject points to threads, which we need to find the thread that a JavaLocalPattern
          // belongs to
          is ThreadObject,
          is JniGlobal,
          is JniLocal,
          is JavaFrame,
          is NativeStack,
          is StickyClass,
          is ThreadBlock,
          is MonitorUsed,
            // TODO What is this and why do we care about it as a root?
          is ReferenceCleanup,
          is JniMonitor
          -> {
            gcRoots.add(record.gcRoot)
          }
        }
      }
      else -> {
        throw IllegalArgumentException("Unexpected record $record")
      }
    }
  }
}