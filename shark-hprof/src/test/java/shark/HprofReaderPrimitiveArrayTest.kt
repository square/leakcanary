package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.text.Charsets.UTF_8

class HprofReaderPrimitiveArrayTest {

  @get:Rule
  var heapDumpRule = HeapDumpRule()

  @Test
  fun skips_primitive_arrays_correctly() {
    val heapDump = heapDumpRule.dumpHeap()

    Hprof.open(heapDump).use { hprof ->
      hprof.reader.readHprofRecords(
        emptySet(), // skip everything including primitive arrays
        OnHprofRecordListener { _, _ -> })
    }
  }

  @Test
  fun reads_primitive_arrays_correctly() {
    val byteArray = ("Sharks also have a sensory organ called the \"ampullae of Lorenzini\" " +
      "which they use to \"feel\" the electrical field coming from its prey.")
      .toByteArray(UTF_8)

    val heapDump = heapDumpRule.dumpHeap()

    var myByteArrayIsInHeapDump = false
    Hprof.open(heapDump).use { hprof ->
      hprof.reader.readHprofRecords(
        setOf(HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord::class),
        OnHprofRecordListener { _, record ->
          if (record is HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump) {
            if (byteArray.contentEquals(record.array)) {
              myByteArrayIsInHeapDump = true
            }
          }
        })
    }
    assertThat(myByteArrayIsInHeapDump).isTrue()
  }
}