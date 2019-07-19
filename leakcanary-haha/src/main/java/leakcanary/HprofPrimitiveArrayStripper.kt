package leakcanary

import leakcanary.HprofPushRecordsParser.OnRecordListener
import leakcanary.Record.HeapDumpEndRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import java.io.File
import kotlin.reflect.KClass

/**
 * Transforms a Hprof to all primitive arrays, which can be useful to remove PII.
 */
class HprofPrimitiveArrayStripper {

  fun stripPrimitiveArrays(
    inputHprofFile: File,
    outputHprofFile: File = File(
        inputHprofFile.parent, inputHprofFile.name.replace(
        ".hprof", "-stripped.hprof"
    ).let { if (it != inputHprofFile.name) it else inputHprofFile.name + "-stripped" })
  ): File {
    val parser = HprofPushRecordsParser()

    lateinit var writer: HprofWriter

    parser.readHprofRecords(inputHprofFile, setOf(object : OnRecordListener {

      override fun recordTypes(): Set<KClass<out Record>> = setOf(Record::class)

      override fun onTypeSizesAvailable(typeSizes: Map<Int, Int>) {
        writer =
          HprofWriter.open(outputHprofFile, idSize = typeSizes.getValue(HprofReader.OBJECT_TYPE))
      }

      override fun onRecord(
        position: Long,
        record: Record
      ) {

        // HprofWriter automatically emits HeapDumpEndRecord, because it flushes
        // all continuous heap dump sub records as one heap dump record.
        if (record is HeapDumpEndRecord) {
          return
        }
        writer.write(
            when (record) {
              is BooleanArrayDump -> BooleanArrayDump(
                  record.id, record.stackTraceSerialNumber, booleanArrayOf()
              )
              is CharArrayDump -> CharArrayDump(
                  record.id, record.stackTraceSerialNumber, charArrayOf()
              )
              is FloatArrayDump -> FloatArrayDump(
                  record.id, record.stackTraceSerialNumber, floatArrayOf()
              )
              is DoubleArrayDump -> DoubleArrayDump(
                  record.id, record.stackTraceSerialNumber, doubleArrayOf()
              )
              is ByteArrayDump -> ByteArrayDump(
                  record.id, record.stackTraceSerialNumber, byteArrayOf()
              )
              is ShortArrayDump -> ShortArrayDump(
                  record.id, record.stackTraceSerialNumber, shortArrayOf()
              )
              is IntArrayDump -> IntArrayDump(
                  record.id, record.stackTraceSerialNumber, intArrayOf()
              )
              is LongArrayDump -> LongArrayDump(
                  record.id, record.stackTraceSerialNumber, longArrayOf()
              )
              else -> {
                record
              }
            }
        )
      }
    })).close()
    writer.close()
    return outputHprofFile
  }

}