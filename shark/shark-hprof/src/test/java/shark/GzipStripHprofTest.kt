package shark

import java.io.File
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import okio.sink
import okio.source
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump

class GzipStripHprofTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `gzipped heap dump strips to the same bytes as that heap dump uncompressed`() {
    val strippedFromHprof = HprofPrimitiveArrayStripper().stripPrimitiveArrays(hprofFile())
    val strippedFromGzip =
      HprofPrimitiveArrayStripper()
        .stripPrimitiveArrays(gzippedHprofFile(), File(testFolder.root, "from-gzip.hprof"))

    assertThat(strippedFromGzip.readBytes()).isEqualTo(strippedFromHprof.readBytes())
  }

  @Test fun `stripping a gzipped heap dump writes a gzipped heap dump`() {
    val strippedFromHprof = HprofPrimitiveArrayStripper().stripPrimitiveArrays(hprofFile())

    val strippedFromGzip = HprofPrimitiveArrayStripper().stripPrimitiveArrays(gzippedHprofFile())

    assertThat(strippedFromGzip.name).isEqualTo("app-stripped.hprof.gz")
    assertThat(strippedFromGzip.length()).isLessThan(strippedFromHprof.length())
    val gunzipped =
      GzipSource(strippedFromGzip.source()).buffer().use { source -> source.readByteArray() }
    assertThat(gunzipped).isEqualTo(strippedFromHprof.readBytes())
  }

  private fun hprofFile() =
    File(testFolder.newFolder(), "app.hprof").apply { writeBytes(heapDumpWithALongArray()) }

  private fun gzippedHprofFile() =
    File(testFolder.newFolder(), "app.hprof.gz").apply {
      GzipSink(sink()).buffer().use { sink -> sink.write(heapDumpWithALongArray()) }
    }

  /** Holds a long array of values that all differ from the zeroes stripping replaces them with. */
  private fun heapDumpWithALongArray(): ByteArray {
    val buffer = Buffer()
    HprofWriter.openWriterFor(buffer, hprofHeader = HprofHeader(heapDumpTimestamp = 42)).use { writer
      ->
      writer.write(
        LongArrayDump(id = 1, stackTraceSerialNumber = 0, array = LongArray(1024) { 0xCAFE })
      )
    }
    return buffer.readByteArray()
  }
}
