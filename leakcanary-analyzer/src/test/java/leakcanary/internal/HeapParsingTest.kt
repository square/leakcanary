package leakcanary.internal

import leakcanary.internal.haha.HeapValue.ObjectReference
import leakcanary.internal.haha.HprofParser
import leakcanary.internal.haha.HprofParser.RecordCallbacks
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

    val keyedWeakReferenceInstances = retrieveKeyedWeakReferenceInstances(parser)

    val targetWeakRef = keyedWeakReferenceInstances.map { parser.hydrateInstance(it) }
        .first { instance ->
          heapDump.referenceKey == parser.retrieveString(instance.fieldValue("key"))
        }

    val referentId = targetWeakRef.fieldValue<ObjectReference>("referent")
        .value

    // Not null
    Assertions.assertThat(referentId).isNotEqualTo(0)

    // TODO find shorter paths to referentId

    parser.close()
  }

  private fun retrieveKeyedWeakReferenceInstances(parser: HprofParser): MutableList<InstanceDumpRecord> {
    var keyedWeakReferenceStringId = -1L
    var keyedWeakReferenceClassId = -1L
    val keyedWeakReferenceInstances = mutableListOf<InstanceDumpRecord>()
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
    parser.scan(callbacks)
    return keyedWeakReferenceInstances
  }
}