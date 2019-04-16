package leakcanary.internal

import leakcanary.internal.haha.GcRoot.JavaFrame
import leakcanary.internal.haha.GcRoot.JniGlobal
import leakcanary.internal.haha.GcRoot.JniLocal
import leakcanary.internal.haha.GcRoot.JniMonitor
import leakcanary.internal.haha.GcRoot.MonitorUsed
import leakcanary.internal.haha.GcRoot.NativeStack
import leakcanary.internal.haha.GcRoot.ReferenceCleanup
import leakcanary.internal.haha.GcRoot.StickyClass
import leakcanary.internal.haha.GcRoot.ThreadBlock
import leakcanary.internal.haha.GcRoot.VmInternal
import leakcanary.internal.haha.HeapValue.ObjectReference
import leakcanary.internal.haha.HprofParser
import leakcanary.internal.haha.HprofParser.RecordCallbacks
import leakcanary.internal.haha.Record.HeapDumpRecord.GcRootRecord
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.internal.haha.Record.LoadClassRecord
import leakcanary.internal.haha.Record.StringRecord
import org.assertj.core.api.Assertions
import org.junit.Test

class HeapParsingTest {

  @Test fun findKeyedWeakReferenceClassInHeapDump() {
    val heapDump = HeapDumpFile.ASYNC_TASK_P
    val file = fileFromName(heapDump.filename)

    val parser = HprofParser.open(file)

    val (gcRootIds, keyedWeakReferenceInstances) = scan(parser)

    val targetWeakRef = keyedWeakReferenceInstances.map { parser.hydrateInstance(it) }
        .first { instance ->
          heapDump.referenceKey == parser.retrieveString(instance.fieldValue("key"))
        }

    val referentId = targetWeakRef.fieldValue<ObjectReference>("referent")
        .value

    // Not null
    Assertions.assertThat(referentId)
        .isNotEqualTo(0)



    // TODO find shorter paths to referentId

    parser.close()
  }

  private fun scan(parser: HprofParser): Pair<List<Long>, List<InstanceDumpRecord>> {
    var keyedWeakReferenceStringId = -1L
    var keyedWeakReferenceClassId = -1L
    val keyedWeakReferenceInstances = mutableListOf<InstanceDumpRecord>()
    val gcRootIds = mutableListOf<Long>()
    val callbacks = RecordCallbacks()
        .on(StringRecord::class.java) {
          if (it.string == "com.squareup.leakcanary.KeyedWeakReference") {
            keyedWeakReferenceStringId = it.id
          }
        }
        .on(LoadClassRecord::class.java) {
          if (it.classNameStringId == keyedWeakReferenceStringId) {
            keyedWeakReferenceClassId = it.id
          }
        }
        .on(InstanceDumpRecord::class.java) {
          if (it.classId == keyedWeakReferenceClassId) {
            keyedWeakReferenceInstances.add(it)
          }
        }
        .on(GcRootRecord::class.java) {
          // TODO Why is ThreadObject ignored?
          when (it.gcRoot) {
            is JniGlobal,
            is JniLocal,
            is JavaFrame,
            is NativeStack,
            is StickyClass,
            is ThreadBlock,
            is MonitorUsed,
              // TODO What is this and why do we care about it as a root?
            is ReferenceCleanup,
            is VmInternal,
            is JniMonitor
            -> {
              gcRootIds.add(it.gcRoot.id)
            }
          }
        }
    parser.scan(callbacks)
    return gcRootIds to keyedWeakReferenceInstances
  }
}