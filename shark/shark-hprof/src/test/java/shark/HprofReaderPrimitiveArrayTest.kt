package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.text.Charsets.UTF_8
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader

class HprofReaderPrimitiveArrayTest {

  @get:Rule
  var heapDumpRule = HeapDumpRule()

  @Test
  fun skips_primitive_arrays_correctly() {
    val heapDump = heapDumpRule.dumpHeap()

    StreamingHprofReader.readerFor(heapDump).readRecords(emptySet()) { _, _, _ ->
      error("Should skip all records, including primitive arrays")
    }
  }

  @Test
  fun reads_primitive_arrays_correctly() {
    val byteArray = ("Sharks also have a sensory organ called the \"ampullae of Lorenzini\" " +
      "which they use to \"feel\" the electrical field coming from its prey.")
      .toByteArray(UTF_8)

    val heapDump = heapDumpRule.dumpHeap()

    var myByteArrayIsInHeapDump = false

    val reader = StreamingHprofReader.readerFor(heapDump).asStreamingRecordReader()
    reader.readRecords(setOf(HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord::class)) {  _, record ->
      if (record is HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump) {
        if (byteArray.contentEquals(record.array)) {
          myByteArrayIsInHeapDump = true
        }
      }
    }
    assertThat(myByteArrayIsInHeapDump).isTrue()
  }
}
