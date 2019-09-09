import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import shark.Hprof
import shark.HprofRecord
import shark.OnHprofRecordListener
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
        val byteArray = "mybytes".toByteArray(UTF_8)

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