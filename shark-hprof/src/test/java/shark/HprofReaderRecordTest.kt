package shark

import org.assertj.core.api.Assertions
import org.junit.Rule
import org.junit.Test

class HprofReaderRecordTest {
  @get:Rule
  var heapDumpRule = HeapDumpRule()

  @Test
  fun reads_hprof_records_correctly() {
    val heapDump = heapDumpRule.dumpHeap()

    Hprof.open(heapDump).use { hprof ->
      hprof.reader.readHprofRecords(
        setOf(HprofRecord::class)
      ) { _, length, record ->
        if (record is HprofRecord.HeapDumpEndRecord) {
          // Only HprofRecord.HeapDumpEndRecord length = 0
          Assertions.assertThat(length == 0L).isTrue()
        } else {
          Assertions.assertThat(length > 0L).isTrue()
        }
      }
    }

  }
}
