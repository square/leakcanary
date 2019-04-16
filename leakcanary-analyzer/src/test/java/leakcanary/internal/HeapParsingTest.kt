package leakcanary.internal

import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.squareup.haha.perflib.Snapshot
import leakcanary.internal.haha.HprofParser
import leakcanary.internal.haha.HprofParser.HydratedInstance
import leakcanary.internal.haha.HprofParser.RecordCallbacks
import leakcanary.internal.haha.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.internal.haha.Record.LoadClassRecord
import leakcanary.internal.haha.Record.StringRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class HeapParsingTest {

  @Test fun findKeyedWeakReferenceClassInHeapDump() {
    val heapDump = HeapDumpFile.ASYNC_TASK_P
    val file = fileFromName(heapDump.filename)

    val before1 = System.nanoTime()
    val parser = HprofParser.open(file)

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

    val targetWeakRef = keyedWeakReferenceInstances.map { parser.hydrateInstance(it) }
        .first { instance ->
          val keyString = parser.retrieveString(instance.fieldValue("key"))
          heapDump.referenceKey == keyString
        }

    val found = parser.retrieveString(targetWeakRef.fieldValue("key"))

    parser.close()

    val after1 = System.nanoTime()

    val buffer = MemoryMappedFileBuffer(file)
    val snapshot = Snapshot.createSnapshot(buffer)

    val keyedWeakReferenceClass = snapshot.findClass("com.squareup.leakcanary.KeyedWeakReference")

    val after2 = System.nanoTime()

    println("First: ${(after1 - before1) / 1000000}ms Second: ${(after2 - after1) / 1000000}ms")
    assertThat(keyedWeakReferenceClassId).isEqualTo(keyedWeakReferenceClass.id)
  }

  fun HydratedInstance.hasField(name: String): Boolean {
    classHierarchy.forEach { hydratedClass ->
      hydratedClass.fieldNames.forEach { fieldName ->
        if (fieldName == name) {
          return true
        }
      }
    }
    return false
  }

}