package shark

import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader

/**
 * A heap dump info record holds an Int heap id and then a string id, so its size only depends on the
 * identifier size once. Android heap dumps, the only ones that carry these records, are dumped with
 * 4 byte identifiers, which makes the two sizes equal and hides a skip that assumes both fields are
 * ids. These read a heap dump written with 8 byte identifiers, where they aren't.
 */
class HeapDumpInfoRecordTest {

  @Test fun `record after a heap dump info record is read when the info record is skipped`() {
    val heapDump = heapDumpWithInfoRecordFollowedBy(longArrayOf(42))

    val arrays = mutableListOf<LongArray>()
    StreamingHprofReader.readerFor(ByteArraySourceProvider(heapDump))
      .asStreamingRecordReader()
      .readRecords(setOf(PrimitiveArrayDumpRecord::class)) { _, record ->
        arrays += (record as LongArrayDump).array
      }

    assertThat(arrays).containsExactly(longArrayOf(42))
  }

  @Test fun `stripping keeps a heap dump info record and the record that follows it`() {
    val heapDump = heapDumpWithInfoRecordFollowedBy(longArrayOf(42))
    val strippedBuffer = Buffer()

    HprofPrimitiveArrayStripper()
      .stripPrimitiveArrays(ByteArraySourceProvider(heapDump), { strippedBuffer })

    val expected = heapDumpWithInfoRecordFollowedBy(longArrayOf(0))
    assertThat(strippedBuffer.readByteArray()).isEqualTo(expected)
  }

  private fun heapDumpWithInfoRecordFollowedBy(array: LongArray): ByteArray {
    val buffer = Buffer()
    HprofWriter.openWriterFor(
      buffer,
      hprofHeader = HprofHeader(heapDumpTimestamp = 42, identifierByteSize = 8)
    ).use { writer ->
      writer.write(HeapDumpInfoRecord(heapId = 1, heapNameStringId = 2))
      writer.write(LongArrayDump(id = 3, stackTraceSerialNumber = 0, array = array))
    }
    return buffer.readByteArray()
  }
}
