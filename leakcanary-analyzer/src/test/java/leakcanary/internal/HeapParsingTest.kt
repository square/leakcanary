package leakcanary.internal

import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.JniGlobal
import leakcanary.GcRoot.JniLocal
import leakcanary.GcRoot.JniMonitor
import leakcanary.GcRoot.MonitorUsed
import leakcanary.GcRoot.NativeStack
import leakcanary.GcRoot.ReferenceCleanup
import leakcanary.GcRoot.StickyClass
import leakcanary.GcRoot.ThreadBlock
import leakcanary.GcRoot.VmInternal
import leakcanary.HeapValue.LongValue
import leakcanary.HprofParser
import leakcanary.HprofParser.RecordCallbacks
import leakcanary.HydratedClass
import leakcanary.updated.KeyedWeakReferenceMirror
import leakcanary.Record.HeapDumpRecord.GcRootRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StringRecord
import leakcanary.updated.internal.ShortestPathFinder
import org.assertj.core.api.Assertions
import org.junit.Test

class HeapParsingTest {

  @Test fun findLeaks() {
    val heapDump = HeapDumpFile.MULTIPLE_LEAKS
    val file = fileFromName(heapDump.filename)

    val parser = HprofParser.open(file)

    val (gcRootIds, heapDumpMemoryStoreClassId, keyedWeakReferenceInstances) = scan(parser)

    val classHierarchy = parser.hydrateClassHierarchy(heapDumpMemoryStoreClassId)

    val retainedWeakRefs =
      findLeakingReferences(parser, classHierarchy, keyedWeakReferenceInstances)

    val pathFinder =
      ShortestPathFinder(
          defaultExcludedRefs.build(), ignoreStrings = true
      )

    val paths = pathFinder.findPaths(parser, retainedWeakRefs, gcRootIds)


    Assertions.assertThat(paths.size)
        .isEqualTo(5)

    parser.close()
  }

  private fun findLeakingReferences(
    parser: HprofParser,
    classHierarchy: List<HydratedClass>,
    keyedWeakReferenceInstances: List<InstanceDumpRecord>
  ): List<KeyedWeakReferenceMirror> {
    val retainedKeysForHeapDump = (parser.retrieveRecord(
        classHierarchy[0].staticFieldValue("retainedKeysForHeapDump")
    ) as ObjectArrayDumpRecord).elementIds.toSet()

    val heapDumpUptimeMillis = classHierarchy[0].staticFieldValue<LongValue>("heapDumpUptimeMillis")
        .value

    return keyedWeakReferenceInstances.map {
      KeyedWeakReferenceMirror.fromInstance(parser.hydrateInstance(it), heapDumpUptimeMillis)
    }
        .filter { retainedKeysForHeapDump.contains(it.key.value) && it.hasReferent }
  }

  private fun scan(parser: HprofParser): Triple<List<Long>, Long, List<InstanceDumpRecord>> {
    var keyedWeakReferenceStringId = -1L
    var heapDumpMemoryStoreStringId = -1L
    var keyedWeakReferenceClassId = -1L
    var heapDumpMemoryStoreClassId = -1L
    val keyedWeakReferenceInstances = mutableListOf<InstanceDumpRecord>()
    val gcRootIds = mutableListOf<Long>()
    val callbacks = RecordCallbacks()
        .on(StringRecord::class.java) {
          if (it.string == "leakcanary.KeyedWeakReference") {
            keyedWeakReferenceStringId = it.id
          } else if (it.string == "leakcanary.HeapDumpMemoryStore") {
            heapDumpMemoryStoreStringId = it.id
          }
        }
        .on(LoadClassRecord::class.java) {
          if (it.classNameStringId == keyedWeakReferenceStringId) {
            keyedWeakReferenceClassId = it.id
          } else if (it.classNameStringId == heapDumpMemoryStoreStringId) {
            heapDumpMemoryStoreClassId = it.id
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
    return Triple(gcRootIds, heapDumpMemoryStoreClassId, keyedWeakReferenceInstances)
  }
}