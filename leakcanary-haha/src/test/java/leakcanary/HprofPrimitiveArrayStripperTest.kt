package leakcanary

import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.PrimitiveType.BOOLEAN
import leakcanary.PrimitiveType.CHAR
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HprofPrimitiveArrayStripperTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  @Test
  fun stripHprof() {
    val hprofFile = testFolder.newFile("temp.hprof")

    val booleanArray = BooleanArrayDump(id, 1, booleanArrayOf(true, false, true, true))
    val charArray = CharArrayDump(id, 1, "Hello World!".toCharArray())
    hprofFile.writeRecords(listOf(booleanArray, charArray))

    val stripper = HprofPrimitiveArrayStripper()

    val strippedFile = stripper.stripPrimitiveArrays(hprofFile)

    strippedFile.readHprof { graph ->
      val booleanArrays = graph.objectSequence()
          .filter { it is GraphPrimitiveArrayRecord && it.primitiveType == BOOLEAN }
          .map { it.readRecord() as BooleanArrayDump }
          .toList()
      assertThat(booleanArrays).hasSize(1)
      assertThat(booleanArrays[0].id).isEqualTo(booleanArray.id)
      assertThat(booleanArrays[0].array).isEmpty()

      val charArrays = graph.objectSequence()
          .filter { it is GraphPrimitiveArrayRecord && it.primitiveType == CHAR }
          .map { it.readRecord() as CharArrayDump }
          .toList()
      assertThat(charArrays).hasSize(1)
      assertThat(charArrays[0].id).isEqualTo(charArray.id)
      assertThat(charArrays[0].array).isEmpty()
    }
  }

  private fun File.writeRecords(
    records: List<Record>
  ) {
    HprofWriter.open(this)
        .use { writer ->
          records.forEach { record ->
            writer.write(record)
          }
        }
  }

  fun File.readHprof(block: (HprofGraph) -> Unit) {
    val (graph, closeable) = HprofGraph.readHprof(this)
    closeable.use {
      block(graph)
    }
  }

}