package shark

import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import java.io.File

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
    Hprof.open(inputHprofFile)
        .use { hprof ->
          val reader = hprof.reader
          HprofWriter.open(outputHprofFile, idSize = reader.objectIdByteSize)
              .use { writer ->
                reader.readHprofRecords(setOf(HprofRecord::class),
                    OnHprofRecordListener { _,
                      record ->
                      // HprofWriter automatically emits HeapDumpEndRecord, because it flushes
                      // all continuous heap dump sub records as one heap dump record.
                      if (record is HeapDumpEndRecord) {
                        return@OnHprofRecordListener
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
                    })
              }
        }
    return outputHprofFile
  }

}